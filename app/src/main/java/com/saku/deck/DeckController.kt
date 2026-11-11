package com.saku.deck

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saku.deck.service.DeckUserService
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, CONNECTING, READY }

data class AppEntry(val label: String, val packageName: String, val component: String)

data class RecentTaskEntry(val taskId: Int, val label: String, val component: String)

data class TouchMapping(
    val id: String,
    val label: String,
    val badge: String,
    val keyCode: Int?,
    val x: Float,
    val y: Float,
)

data class ControlPosition(val id: String, val x: Float, val y: Float)

/** Owns the Shizuku helper connection, the virtual display and all input sent to it. */
class DeckController(private val app: Application) {

    var shizukuState by mutableStateOf(ShizukuState.NOT_RUNNING)
        private set
    var displayId by mutableIntStateOf(-1)
        private set
    var currentApp by mutableStateOf<AppEntry?>(null)
    var recentTasks by mutableStateOf<List<RecentTaskEntry>>(emptyList())
        private set
    var recentsOpen by mutableStateOf(false)
        private set
    var pickerOpen by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var touchMappings by mutableStateOf(loadTouchMappings())
        private set
    var controlPositions by mutableStateOf(loadControlPositions())
        private set
    var controlSizes by mutableStateOf(loadControlSizes())
        private set
    var controlSizeMenuOpen by mutableStateOf(false)
    var mappingMenuOpen by mutableStateOf(false)
    var mappingTargetId by mutableStateOf<String?>(null)
    var controlLayoutEditing by mutableStateOf(false)
        private set
    var externalKeyboardOpen by mutableStateOf(false)
    var gamepadHidden by mutableStateOf(loadGamepadHidden())
        private set
    var hiddenControls by mutableStateOf(loadHiddenControls())
        private set
    var visibilityMenuOpen by mutableStateOf(false)
    var assistantMenuOpen by mutableStateOf(false)
    var titanPortraitMode by mutableStateOf(loadTitanPortraitMode())
    var virtualFps by mutableIntStateOf(0)
        private set

    // Cursor position for the Titan trackpad, as a 0..1 fraction of the virtual
    // display size. Stays negative until the trackpad is first used. The host UI
    // draws its own pointer sprite at this position: Android only renders the
    // native mouse cursor for real mouse devices, never for injected events.
    var trackpadCursorX by mutableFloatStateOf(-1f)
        private set
    var trackpadCursorY by mutableFloatStateOf(-1f)
        private set

    // Low-resolution render mode: the virtual display renders at 80% (density
    // scaled by the same factor so the on-screen layout is unchanged) and
    // SurfaceFlinger upscales it, cutting real GPU load on low-end chips.
    var performanceMode by mutableStateOf(loadPerformanceMode())
        private set

    /**
     * Measure the foreground app's real SurfaceFlinger presentation cadence.
     * Each sample is validated against monotonic time so a frozen layer or
     * stale latency ring cannot leave an old FPS value on screen.
     */
    fun refreshVirtualFps() {
        val svc = service
        val pkg = currentApp?.packageName
        if (svc == null || pkg == null || displayId < 0) {
            virtualFps = 0
            fpsCapturePackage = null
            fpsLastValidAtMs = 0L
            return
        }
        if (fpsCapturePackage != pkg) {
            fpsCapturePackage = pkg
            fpsLastValidAtMs = 0L
            virtualFps = 0
        }
        if (!fpsQueryRunning.compareAndSet(false, true)) return

        fpsWorker.execute {
            val measured = try {
                captureSurfaceFlingerLayerFps(svc, pkg)
            } catch (_: Throwable) {
                null
            }
            main.post {
                if (service !== svc || currentApp?.packageName != pkg || displayId < 0) return@post
                val now = SystemClock.elapsedRealtime()
                if (measured != null) {
                    virtualFps = measured.coerceIn(0, 240)
                    fpsLastValidAtMs = now
                } else if (fpsLastValidAtMs == 0L || now - fpsLastValidAtMs > 2200L) {
                    // Do not freeze the last good number indefinitely when the
                    // game stops rendering, is backgrounded, or changes layers.
                    virtualFps = 0
                }
            }
            fpsQueryRunning.set(false)
        }
    }

    private data class LayerFpsSample(
        val fps: Int,
        val frameCount: Int,
        val latestPresentNs: Long,
        val layerName: String,
    )

    private fun captureSurfaceFlingerLayerFps(svc: IDeckService, packageName: String): Int? {
        val listing = svc.runShell("dumpsys SurfaceFlinger --list")
        if (listing.isBlank() || listing.startsWith("Error:", ignoreCase = true)) return null

        // SurfaceView/BLAST layers are normally the app's continuously rendered
        // content. Limit shell round-trips so an app with many child layers
        // cannot stall the polling worker.
        val appLayers = listing.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(packageName, ignoreCase = true) }
            .distinct()
            .toList()
        val primaryLayers = appLayers.filter {
            it.contains("SurfaceView", ignoreCase = true) || it.contains("BLAST", ignoreCase = true)
        }
        val layers = (primaryLayers.ifEmpty { appLayers }).take(5)
        if (layers.isEmpty()) return null

        val nowNs = System.nanoTime()
        val samples = layers.mapNotNull { layer ->
            val safeLayer = layer.replace("'", "'\\''")
            val output = svc.runShell("dumpsys SurfaceFlinger --latency '$safeLayer'")
            parseSurfaceFlingerLatencySample(output, layer, nowNs)
        }
        // Prefer the layer with the most recent frames, then the largest sample
        // window. This avoids choosing an unrelated tiny animation layer merely
        // because its short interval happens to calculate a higher FPS.
        return samples.maxWithOrNull(
            compareBy<LayerFpsSample> { it.frameCount }
                .thenBy { it.latestPresentNs }
                .thenBy { it.fps }
        )?.fps
    }

    private fun parseSurfaceFlingerLatencySample(
        output: String,
        layerName: String,
        nowNs: Long,
    ): LayerFpsSample? {
        if (output.isBlank() || output.startsWith("Error:", ignoreCase = true)) return null
        val rows = output.lineSequence().drop(1).mapNotNull { line ->
            val columns = line.trim().split(Regex("\\s+"))
            if (columns.size < 3) null else columns[1].toLongOrNull()
        }.filter { it > 0L && it <= nowNs + 100_000_000L }
            .distinct()
            .sorted()
            .toList()

        // Ignore entries older than 2.2 seconds: SurfaceFlinger keeps a ring
        // buffer, so sorting the whole buffer otherwise reports historical FPS.
        val fresh = rows.filter { nowNs - it <= 2_200_000_000L }
        if (fresh.size < 4) return null
        val window = fresh.takeLast(60)
        val elapsedNs = window.last() - window.first()
        if (elapsedNs <= 0L) return null
        val fps = (window.size - 1) * 1_000_000_000.0 / elapsedNs.toDouble()
        if (!fps.isFinite() || fps <= 0.0) return null
        return LayerFpsSample(
            fps = kotlin.math.round(fps).toInt().coerceIn(1, 240),
            frameCount = window.size,
            latestPresentNs = window.last(),
            layerName = layerName,
        )
    }

    fun toggleTitanPortraitMode() {
        titanPortraitMode = !titanPortraitMode
        app.getSharedPreferences("saku_deck_display_mode", Context.MODE_PRIVATE)
            .edit().putBoolean("titan_portrait", titanPortraitMode).apply()
        assistantMenuOpen = false
        message = if (titanPortraitMode) "Mode Unihertz Titan aktif." else "Mode Landscape aktif."
    }

    fun togglePerformanceMode() {
        performanceMode = !performanceMode
        app.getSharedPreferences(PERFORMANCE_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("low_resolution", performanceMode).apply()
        message = if (performanceMode) {
            "Mode performa aktif: resolusi render virtual display diturunkan untuk FPS stabil."
        } else {
            "Mode kualitas penuh aktif."
        }
        ensureDisplay()
    }

    private fun loadPerformanceMode(): Boolean =
        app.getSharedPreferences(PERFORMANCE_PREFS, Context.MODE_PRIVATE)
            .getBoolean("low_resolution", false)

    private fun loadTitanPortraitMode(): Boolean =
        app.getSharedPreferences("saku_deck_display_mode", Context.MODE_PRIVATE)
            .getBoolean("titan_portrait", false)

    @Volatile
    private var service: IDeckService? = null
    private var binding = false
    @Volatile
    private var hostActive = true
    @Volatile
    private var shuttingDown = false
    private val shutdownCallbacks = mutableListOf<() -> Unit>()
    private var pendingEnsure = false
    private val worker = Executors.newSingleThreadExecutor()
    // Keep Binder input injection off the Compose/UI thread; preserve event order.
    private val inputWorker = Executors.newSingleThreadExecutor()
    private val fpsWorker = Executors.newSingleThreadExecutor()
    private val fpsQueryRunning = AtomicBoolean(false)
    @Volatile private var fpsCapturePackage: String? = null
    @Volatile private var fpsLastValidAtMs: Long = 0L
    private val main = Handler(Looper.getMainLooper())

    private var surface: Surface? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private var displayW = 0
    private var displayH = 0

    // lx, ly, rx, ry, left trigger, right trigger
    private val axes = FloatArray(6)
    private val keyDownTimes = HashMap<Int, Long>()
    private data class ActiveTouch(val id: Int, var x: Float, var y: Float)
    private val touchLock = Any()
    private val activeTouches = LinkedHashMap<String, ActiveTouch>()
    private var touchDownTime = 0L

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, DeckUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("deck")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            service = IDeckService.Stub.asInterface(binder)
            main.post {
                binding = false
                shizukuState = ShizukuState.READY
                message = null
                ensureDisplay()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            fpsCapturePackage = null
            fpsLastValidAtMs = 0L
            main.post {
                binding = false
                virtualFps = 0
                displayId = -1
                currentApp = null
                pickerOpen = true
                refresh()
            }
        }
    }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky { main.post { refresh() } }
        Shizuku.addBinderDeadListener {
            main.post {
                service = null
                fpsCapturePackage = null
                fpsLastValidAtMs = 0L
                binding = false
                virtualFps = 0
                displayId = -1
                currentApp = null
                pickerOpen = true
                refresh()
            }
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> main.post { refresh() } }
        refresh()
    }

    fun onHostResumed() {
        hostActive = true
        // Do not cancel an in-flight teardown. Its completion callback will
        // reopen the Shizuku connection after the old display is fully released.
        if (!shuttingDown) refresh()
    }

    fun refresh() {
        if (!hostActive || shuttingDown) return
        if (service != null) {
            shizukuState = ShizukuState.READY
            return
        }
        shizukuState = try {
            when {
                !Shizuku.pingBinder() ->
                    if (isShizukuInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
                Shizuku.isPreV11() -> {
                    message = "Versi Shizuku terlalu lama. Perbarui Shizuku dulu."
                    ShizukuState.NOT_RUNNING
                }
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    ShizukuState.NO_PERMISSION
                else -> {
                    bind()
                    ShizukuState.CONNECTING
                }
            }
        } catch (t: Throwable) {
            message = "Shizuku error: ${t.message}"
            ShizukuState.NOT_RUNNING
        }
    }

    private fun bind() {
        if (binding) return
        binding = true
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (t: Throwable) {
            binding = false
            message = "Gagal menyalakan layanan Shizuku: ${t.message}"
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                message = "Izin pernah ditolak. Buka Shizuku → Aplikasi yang diizinkan → nyalakan Saku Deck."
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (t: Throwable) {
            message = "Shizuku error: ${t.message}"
        }
    }

    fun openShizuku(ctx: Context) {
        val launch = app.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun isShizukuInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    // ---- surface & display --------------------------------------------------

    fun onSurfaceReady(s: Surface, width: Int, height: Int) {
        surface = s
        surfaceW = width
        surfaceH = height
        ensureDisplay()
    }

    fun onSurfaceGone() {
        // A Surface can be recreated while the Activity remains visible.
        // Keep the display lifecycle under the host Activity; shutdown() releases it.
        releaseAllTouchPointers()
        surface = null
        surfaceW = 0
        surfaceH = 0
    }

    fun retryDisplay() {
        message = null
        if (service == null) refresh() else ensureDisplay()
    }

    private fun ensureDisplay() {
        if (!hostActive || shuttingDown) return
        val svc = service ?: return
        val s = surface ?: return
        if (surfaceW <= 0 || surfaceH <= 0) return
        if (busy) {
            pendingEnsure = true
            return
        }
        val quality = if (performanceMode) PERFORMANCE_RENDER_SCALE else 1f
        val w = ((surfaceW * quality).toInt() and 1.inv()).coerceAtLeast(64)
        val h = ((surfaceH * quality).toInt() and 1.inv()).coerceAtLeast(64)
        val dpi = (app.resources.displayMetrics.densityDpi * DPI_SCALE * quality).toInt().coerceAtLeast(120)
        val existing = displayId
        busy = true
        worker.execute {
            val result = runCatching {
                if (existing >= 0) {
                    svc.setSurface(Bundle().apply { putParcelable("surface", s) })
                    if (w != displayW || h != displayH) svc.resizeDisplay(w, h, dpi)
                    Bundle().apply { putInt("displayId", existing) }
                } else {
                    svc.createDisplay(Bundle().apply {
                        putParcelable("surface", s)
                        putInt("width", w)
                        putInt("height", h)
                        putInt("dpi", dpi)
                    })
                }
            }
            main.post {
                busy = false
                result.onSuccess { b ->
                    val id = b.getInt("displayId", -1)
                    if (id >= 0) {
                        displayId = id
                        displayW = w
                        displayH = h
                        if (existing < 0) {
                            // A fresh display starts without a cursor position.
                            mouseCursorInitialized = false
                            trackpadCursorX = -1f
                            trackpadCursorY = -1f
                        }
                    } else {
                        message = "Gagal membuat layar virtual: ${b.getString("error")}"
                    }
                }.onFailure {
                    message = "Gagal membuat layar virtual: ${it.message}"
                }
                if (pendingEnsure) {
                    pendingEnsure = false
                    ensureDisplay()
                }
            }
        }
    }

    /**
     * Idempotently tears down all resources owned by the deck.
     * Touch UP/CANCEL events are queued first; display release and Shizuku unbind
     * are queued behind them on the same input executor so they cannot overtake
     * an outstanding pointer release.
     */
    fun shutdown(completion: (() -> Unit)? = null) {
        if (completion != null) synchronized(shutdownCallbacks) {
            shutdownCallbacks.add(completion)
        }
        if (shuttingDown) return
        hostActive = false
        shuttingDown = true
        pendingEnsure = false
        releaseAllTouchPointers()

        val svc = service
        val wasBinding = binding
        val stopFpsCapture = fpsCapturePackage != null
        binding = false
        displayId = -1
        displayW = 0
        displayH = 0
        currentApp = null
        virtualFps = 0
        fpsCapturePackage = null
        fpsLastValidAtMs = 0L
        pickerOpen = true
        busy = false
        keyDownTimes.clear()
        mouseCursorInitialized = false
        trackpadCursorX = -1f
        trackpadCursorY = -1f

        // First wait behind any display create/resize task, then queue teardown
        // behind all input events. This prevents release racing display creation.
        runCatching {
            worker.execute {
                runCatching {
                    inputWorker.execute {
                        try {
                            if (svc != null) {
                                if (stopFpsCapture) {
                                    runCatching { svc.runShell("dumpsys SurfaceFlinger --timestats -disable") }
                                }
                                runCatching { svc.releaseDisplay() }
                            }
                            if (wasBinding || svc != null) {
                                runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
                            }
                        } finally {
                            service = null
                            main.post {
                                if (hostActive) {
                                    // The Activity resumed while teardown was in flight.
                                    shuttingDown = false
                                    refresh()
                                } else {
                                    shizukuState = ShizukuState.CONNECTING
                                }
                                completeShutdownCallbacks()
                            }
                        }
                    }
                }.onFailure {
                    if (svc != null) {
                        if (stopFpsCapture) {
                            runCatching { svc.runShell("dumpsys SurfaceFlinger --timestats -disable") }
                        }
                        runCatching { svc.releaseDisplay() }
                    }
                    if (wasBinding || svc != null) runCatching {
                        Shizuku.unbindUserService(userServiceArgs, connection, true)
                    }
                    service = null
                    main.post { completeShutdownCallbacks() }
                }
            }
        }.onFailure {
            // Best-effort fallback if the worker is no longer accepting tasks.
            if (svc != null) {
                if (fpsCapturePackage != null) {
                    runCatching { svc.runShell("dumpsys SurfaceFlinger --timestats -disable") }
                }
                runCatching { svc.releaseDisplay() }
            }
            if (wasBinding || svc != null) runCatching {
                Shizuku.unbindUserService(userServiceArgs, connection, true)
            }
            service = null
            main.post { completeShutdownCallbacks() }
        }
    }

    private fun completeShutdownCallbacks() {
        val callbacks = synchronized(shutdownCallbacks) {
            shutdownCallbacks.toList().also { shutdownCallbacks.clear() }
        }
        callbacks.forEach { callback -> runCatching { callback() } }
    }

    // ---- apps ---------------------------------------------------------------

    fun openPicker() {
        recentsOpen = false
        pickerOpen = true
    }

    fun openRecentTasks() {
        val svc = service ?: return
        val targetDisplay = displayId
        if (targetDisplay < 0) return
        worker.execute {
            val raw = runCatching { svc.getOpenTasks(targetDisplay) }.getOrDefault("")
            val entries = raw.lineSequence().mapNotNull { line ->
                val parts = line.split(" • ")
                val taskId = parts.firstOrNull()?.removePrefix("taskId=")?.toIntOrNull()
                    ?: return@mapNotNull null
                val component = parts.getOrNull(1) ?: return@mapNotNull null
                val activity = component.substringAfter('/').substringAfterLast('.')
                RecentTaskEntry(taskId, resolveRecentTaskLabel(component), component)
            }.distinctBy { it.taskId }
            main.post {
                if (displayId != targetDisplay || service !== svc) return@post
                recentTasks = entries
                recentsOpen = true
                pickerOpen = false
                if (entries.isEmpty()) message = "Belum ada aplikasi terbuka pada virtual display."
            }
        }
    }

    fun focusRecentTask(entry: RecentTaskEntry) {
        val svc = service ?: return
        val targetDisplay = displayId
        if (targetDisplay < 0) return
        worker.execute {
            val result = runCatching { svc.focusOpenTask(targetDisplay, entry.taskId) }
                .getOrElse { "Gagal memfokuskan aplikasi: ${it.message}" }
            main.post {
                if (displayId != targetDisplay || service !== svc) return@post
                recentsOpen = false
                message = result
            }
        }
    }

    fun closeRecentTask(entry: RecentTaskEntry) {
        val svc = service ?: return
        val targetDisplay = displayId
        if (targetDisplay < 0) return
        worker.execute {
            val result = runCatching { svc.closeOpenTask(targetDisplay, entry.taskId) }
                .getOrElse { "Gagal menutup aplikasi: ${it.message}" }
            val refreshed = runCatching { svc.getOpenTasks(targetDisplay) }.getOrDefault("")
            val entries = refreshed.lineSequence().mapNotNull { line ->
                val parts = line.split(" • ")
                val taskId = parts.firstOrNull()?.removePrefix("taskId=")?.toIntOrNull()
                    ?: return@mapNotNull null
                val component = parts.getOrNull(1) ?: return@mapNotNull null
                RecentTaskEntry(taskId, resolveRecentTaskLabel(component), component)
            }.distinctBy { it.taskId }
            main.post {
                if (displayId != targetDisplay || service !== svc) return@post
                recentTasks = entries
                message = result
            }
        }
    }

    fun closeRecentTasks() {
        recentsOpen = false
    }

    private fun resolveRecentTaskLabel(component: String): String {
        val name = ComponentName.unflattenFromString(component) ?: return component
        return runCatching { app.packageManager.getActivityInfo(name, 0).loadLabel(app.packageManager).toString() }
            .getOrDefault(name.packageName)
    }

    fun closePicker() {
        if (currentApp != null) pickerOpen = false
    }

    fun launch(entry: AppEntry) {
        val svc = service ?: return
        val id = displayId
        if (id < 0) return
        pickerOpen = false
        currentApp = entry
        virtualFps = 0
        fpsCapturePackage = null
        message = null
        worker.execute {
            val out = runCatching { svc.launchApp(entry.component, id) }
                .getOrElse { "Error: ${it.message}" }
            main.post {
                when {
                    out.contains("brought to the front", true) || out.contains("current task", true) ->
                        message = "${entry.label} sudah terbuka di layar utama. Tutup dulu dari menu aplikasi terbaru, lalu buka lagi di sini."
                    out.contains("Error", true) || out.contains("Exception", true) ->
                        message = "Gagal membuka ${entry.label}: ${out.trim().take(200)}"
                }
            }
        }
    }

    // ---- Mobile Legends touch mapping --------------------------------------

    fun openMappingMenu() { mappingMenuOpen = true }
    fun closeMappingMenu() { mappingMenuOpen = false }

    fun armMapping(id: String) {
        val target = touchMappings.firstOrNull { it.id == id } ?: return
        mappingMenuOpen = false
        mappingTargetId = id
        message = "Atur ${target.label}: ketuk posisi tombol itu pada layar game."
    }

    fun setMappingPosition(id: String, x: Float, y: Float) {
        val target = touchMappings.firstOrNull { it.id == id } ?: return
        val updated = touchMappings.map {
            if (it.id == id) it.copy(x = x.coerceIn(0.02f, 0.98f), y = y.coerceIn(0.02f, 0.98f)) else it
        }
        touchMappings = updated
        val saved = updated.first { it.id == id }
        val prefs = app.getSharedPreferences(MAPPING_PREFS, Context.MODE_PRIVATE).edit()
        prefs.putFloat("${id}_x", saved.x)
        prefs.putFloat("${id}_y", saved.y)
        prefs.apply()
        mappingTargetId = null
        message = "${saved.label} tersimpan di ${kotlin.math.round(saved.x * 100).toInt()}%, ${kotlin.math.round(saved.y * 100).toInt()}%."
    }

    fun cancelMapping() {
        mappingTargetId = null
        mappingMenuOpen = false
    }

    fun checkInputStatus() {
        val svc = service ?: run {
            message = "Shizuku belum terhubung. Izin dan layanan perlu aktif sebelum touch bisa diuji."
            return
        }
        worker.execute {
            val result = runCatching { svc.getInputStatus() }
                .getOrElse { "Tidak dapat membaca status input: ${it.message}" }
            main.post { message = "Status touch: $result" }
        }
    }

    fun resetMobileLegendsMapping() {
        touchMappings = DEFAULT_TOUCH_MAPPINGS
        val prefs = app.getSharedPreferences(MAPPING_PREFS, Context.MODE_PRIVATE).edit().clear()
        DEFAULT_TOUCH_MAPPINGS.forEach {
            prefs.putFloat("${it.id}_x", it.x)
            prefs.putFloat("${it.id}_y", it.y)
        }
        prefs.apply()
        mappingTargetId = null
        mappingMenuOpen = false
        message = "Preset Mobile Legends dipulihkan. Sesuaikan posisi jika layout game berbeda."
    }

    fun setControlSize(id: String, sizeDp: Float) {
        if (id !in FACE_CONTROL_IDS) return
        val safeSize = sizeDp.coerceIn(MIN_FACE_SIZE_DP, MAX_FACE_SIZE_DP)
        controlSizes = controlSizes + (id to safeSize)
        app.getSharedPreferences(CONTROL_SIZE_PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(id, safeSize).apply()
    }

    fun resetControlSizes() {
        controlSizes = DEFAULT_FACE_CONTROL_SIZES
        val prefs = app.getSharedPreferences(CONTROL_SIZE_PREFS, Context.MODE_PRIVATE).edit().clear()
        DEFAULT_FACE_CONTROL_SIZES.forEach { (id, size) -> prefs.putFloat(id, size) }
        prefs.apply()
    }

    private fun loadControlSizes(): Map<String, Float> {
        val prefs = app.getSharedPreferences(CONTROL_SIZE_PREFS, Context.MODE_PRIVATE)
        return DEFAULT_FACE_CONTROL_SIZES.mapValues { (id, default) ->
            prefs.getFloat(id, default).coerceIn(MIN_FACE_SIZE_DP, MAX_FACE_SIZE_DP)
        }
    }

    fun beginControlLayoutEdit() {
        mappingMenuOpen = false
        controlLayoutEditing = true
        message = "Seret X/Y/A/B, Spell, Regen, Recall, dan analog kanan ke posisi yang diinginkan, lalu tekan SIMPAN."
    }

    fun moveControlPosition(id: String, x: Float, y: Float, marginX: Float = 0.12f, marginY: Float = 0.12f) {
        if (!controlLayoutEditing) return
        val minX = marginX.coerceIn(0.05f, 0.45f)
        val minY = marginY.coerceIn(0.05f, 0.45f)
        controlPositions = controlPositions.map {
            if (it.id == id) it.copy(x = x.coerceIn(minX, 1f - minX), y = y.coerceIn(minY, 1f - minY)) else it
        }
    }

    fun saveControlLayout() {
        val prefs = app.getSharedPreferences(CONTROL_LAYOUT_PREFS, Context.MODE_PRIVATE).edit().clear()
        controlPositions.forEach {
            prefs.putFloat("${it.id}_x", it.x)
            prefs.putFloat("${it.id}_y", it.y)
        }
        prefs.apply()
        controlLayoutEditing = false
        message = "Posisi tombol dan analog berhasil disimpan."
    }

    fun cancelControlLayoutEdit() {
        controlPositions = loadControlPositions()
        controlLayoutEditing = false
        message = "Perubahan layout dibatalkan."
    }

    fun resetControlLayout() {
        controlPositions = DEFAULT_CONTROL_POSITIONS
        message = "Layout awal dipulihkan. Tekan SIMPAN untuk menyimpan."
    }

    fun updateExternalKeyboardOpen(open: Boolean) {
        externalKeyboardOpen = open
    }

    private var mouseCursorX = 0f
    private var mouseCursorY = 0f
    private var mouseCursorInitialized = false
    private var mouseDownTime = 0L
    private var lastHoverSentAt = 0L
    private var trackpadContactActive = false
    private var tapRelease: Runnable? = null
    private var lastMappedMoveAt = 0L
    private var lastAxesSentAt = 0L
    private var lastAxesSent = FloatArray(6)

    /**
     * Relative trackpad: movement updates an internal cursor position that the
     * host renders as an overlay sprite inside the virtual screen. Mouse hover
     * events are still forwarded so mouse-aware apps react to the pointer.
     */
    fun moveTrackpadCursor(deltaX: Float, deltaY: Float, padWidth: Int, padHeight: Int) {
        val svc = service ?: return
        val id = displayId
        if (id < 0 || displayW <= 0 || displayH <= 0 || padWidth <= 0 || padHeight <= 0) return
        ensureTrackpadCursor()
        // Pointer ballistics like a real desktop cursor: slow drags move
        // precisely, quick flicks travel further. This replaces the old
        // constant-speed scaling that made the trackpad feel slippery.
        val travel = kotlin.math.hypot(deltaX, deltaY)
        val speed = travel / padWidth.coerceAtLeast(1)
        // While dragging, the held contact must follow the finger 1:1 or
        // drags feel amplified. Free cursor movement uses ballistics.
        val gain = if (trackpadContactActive) 1f
        else TRACKPAD_MIN_GAIN +
            (TRACKPAD_MAX_GAIN - TRACKPAD_MIN_GAIN) * (speed / 1.2f).coerceIn(0f, 1f)
        mouseCursorX = (mouseCursorX + deltaX * displayW / padWidth * gain)
            .coerceIn(1f, (displayW - 2).coerceAtLeast(1).toFloat())
        mouseCursorY = (mouseCursorY + deltaY * displayH / padHeight * gain)
            .coerceIn(1f, (displayH - 2).coerceAtLeast(1).toFloat())
        trackpadCursorX = mouseCursorX / displayW
        trackpadCursorY = mouseCursorY / displayH
        if (trackpadContactActive) {
            // While dragging, the held touch contact follows the cursor.
            setMappedTouch(TRACKPAD_TOUCH_KEY, mouseCursorX, mouseCursorY, true)
            return
        }
        val now = SystemClock.uptimeMillis()
        // Mouse-aware apps still receive hover moves, throttled to roughly one
        // event per frame so the Binder input queue can never outpace the game.
        if (now - lastHoverSentAt >= 16L) {
            lastHoverSentAt = now
            dispatchMouseEvent(svc, id, MotionEvent.ACTION_HOVER_MOVE, 0, now)
        }
    }

    private fun ensureTrackpadCursor() {
        if (!mouseCursorInitialized && displayW > 0 && displayH > 0) {
            mouseCursorX = displayW * 0.5f
            mouseCursorY = displayH * 0.5f
            trackpadCursorX = 0.5f
            trackpadCursorY = 0.5f
            mouseCursorInitialized = true
        }
    }

    fun tapTrackpadCursor() {
        ensureTrackpadCursor()
        if (!mouseCursorInitialized || displayId < 0) return
        // Tap as a real touchscreen contact instead of a mouse click: every app
        // accepts touch, while injected mouse buttons are ignored by many apps
        // and Android never draws its cursor sprite for injected mouse events.
        val x = mouseCursorX
        val y = mouseCursorY
        // Cancel a release still pending from the previous tap: without this
        // a fast double tap let the old release cancel the fresh press.
        tapRelease?.let { main.removeCallbacks(it) }
        setMappedTouch(TRACKPAD_TOUCH_KEY, x, y, true)
        val release = Runnable { setMappedTouch(TRACKPAD_TOUCH_KEY, x, y, false) }
        tapRelease = release
        main.postDelayed(release, 65L)
    }

    /** Dextop-style press-and-hold: a held trackpad contact becomes a real drag. */
    fun trackpadPress(down: Boolean) {
        ensureTrackpadCursor()
        if (!mouseCursorInitialized || displayId < 0) return
        trackpadContactActive = down
        setMappedTouch(TRACKPAD_TOUCH_KEY, mouseCursorX, mouseCursorY, down)
    }

    /** Two-finger trackpad gesture translated into a mouse wheel scroll. */
    fun scrollTrackpad(horizontal: Float, vertical: Float) {
        val svc = service ?: return
        val id = displayId
        if (!mouseCursorInitialized || id < 0 || (horizontal == 0f && vertical == 0f)) return
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            this.id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = mouseCursorX
            y = mouseCursorY
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
        })
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try { inject(svc, event, id) } finally { event.recycle() }
    }

    private fun dispatchMouseEvent(
        svc: IDeckService,
        id: Int,
        action: Int,
        buttons: Int,
        eventTime: Long
    ) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            this.id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = mouseCursorX
            y = mouseCursorY
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        })
        val downTime = if (action == MotionEvent.ACTION_DOWN) eventTime
            else mouseDownTime.takeIf { it > 0L } ?: eventTime
        val event = MotionEvent.obtain(
            downTime, eventTime, action, 1, props, coords,
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try { inject(svc, event, id) } finally { event.recycle() }
    }

    fun toggleGamepadHidden() {
        gamepadHidden = !gamepadHidden
        app.getSharedPreferences(CONTROL_VISIBILITY_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("hide_all", gamepadHidden).apply()
    }

    fun setControlHidden(id: String, hidden: Boolean) {
        hiddenControls = if (hidden) hiddenControls + id else hiddenControls - id
        app.getSharedPreferences(CONTROL_VISIBILITY_PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet("hidden_controls", hiddenControls).apply()
    }

    private fun loadGamepadHidden(): Boolean =
        app.getSharedPreferences(CONTROL_VISIBILITY_PREFS, Context.MODE_PRIVATE)
            .getBoolean("hide_all", false)

    private fun loadHiddenControls(): Set<String> =
        app.getSharedPreferences(CONTROL_VISIBILITY_PREFS, Context.MODE_PRIVATE)
            .getStringSet("hidden_controls", emptySet())?.toSet() ?: emptySet()

    private fun loadControlPositions(): List<ControlPosition> {
        val prefs = app.getSharedPreferences(CONTROL_LAYOUT_PREFS, Context.MODE_PRIVATE)
        return DEFAULT_CONTROL_POSITIONS.map {
            it.copy(
                x = prefs.getFloat("${it.id}_x", it.x).coerceIn(0.12f, 0.88f),
                y = prefs.getFloat("${it.id}_y", it.y).coerceIn(0.12f, 0.88f),
            )
        }
    }

    private fun loadTouchMappings(): List<TouchMapping> {
        val prefs = app.getSharedPreferences(MAPPING_PREFS, Context.MODE_PRIVATE)
        return DEFAULT_TOUCH_MAPPINGS.map {
            it.copy(
                x = prefs.getFloat("${it.id}_x", it.x).coerceIn(0.02f, 0.98f),
                y = prefs.getFloat("${it.id}_y", it.y).coerceIn(0.02f, 0.98f),
            )
        }
    }

    // ---- input --------------------------------------------------------------

    fun sendKey(keyCode: Int, down: Boolean) {
        // Generic keyboard/gamepad input must never trigger system navigation.
        // Back is sent only through the dedicated navigation action below.
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH || keyCode == KeyEvent.KEYCODE_BACK) return
        sendKeyInternal(keyCode, down)
    }

    /** Sends Back only for an explicit navigation control, never for text-key input. */
    fun sendNavigationBack(down: Boolean) {
        sendKeyInternal(KeyEvent.KEYCODE_BACK, down)
    }

    private fun sendKeyInternal(keyCode: Int, down: Boolean) {
        // Overview is disabled for the duration of the Deck session.
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) return
        val svc = service ?: return
        val id = displayId
        if (id < 0) return
        val mapping = touchMappings.firstOrNull { it.keyCode == keyCode }
        if (mapping != null) {
            setMappedTouch("mapped:${mapping.id}", mapping.x * displayW, mapping.y * displayH, down)
            return
        }
        val now = SystemClock.uptimeMillis()
        val downTime = if (down) now.also { keyDownTimes[keyCode] = it } else keyDownTimes.remove(keyCode) ?: now
        // Standard text/navigation keys must identify as a keyboard source.
        // Only controller buttons are tagged as gamepad events; otherwise
        // editors may ignore Backspace, Enter and other Titan keyboard keys.
        val source = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_Z,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE -> InputDevice.SOURCE_GAMEPAD
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                InputDevice.SOURCE_DPAD or InputDevice.SOURCE_GAMEPAD
            else -> InputDevice.SOURCE_KEYBOARD
        }
        val ev = KeyEvent(
            downTime, now,
            if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source
        )
        inject(svc, ev, id)
    }

    /** Sends standard desktop editing shortcuts to the focused editor on the virtual display. */
    fun sendShortcut(keyCode: Int) {
        val svc = service ?: return
        val id = displayId
        if (id < 0) return
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON
        val down = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        val upTime = SystemClock.uptimeMillis()
        val up = KeyEvent(
            now, upTime, KeyEvent.ACTION_UP, keyCode, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        inject(svc, down, id)
        inject(svc, up, id)
    }

    /** A skill-wheel button holds the mapped contact, moves it while dragged, then releases it. */
    fun sendSkillWheel(keyCode: Int, dragX: Float, dragY: Float, pressed: Boolean) {
        val mapping = touchMappings.firstOrNull { it.keyCode == keyCode }
        if (mapping == null) {
            sendKey(keyCode, pressed)
            return
        }
        val reachX = displayW * 0.18f
        val reachY = displayH * 0.18f
        val x = (mapping.x * displayW + dragX.coerceIn(-1.5f, 1.5f) * reachX)
            .coerceIn(0f, displayW.toFloat())
        val y = (mapping.y * displayH + dragY.coerceIn(-1.5f, 1.5f) * reachY)
            .coerceIn(0f, displayH.toFloat())
        setMappedTouch("mapped:${mapping.id}", x, y, pressed)
    }

    /** Converts committed text from the host-display IME into key events on the virtual display. */
    fun sendText(text: String): Boolean {
        if (text.isEmpty()) return true
        val svc = service ?: return false
        val id = displayId
        if (id < 0) return false
        val events = runCatching {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        }.getOrNull()
        if (events == null) {
            message = "Karakter ini tidak didukung oleh keyboard virtual. Coba huruf, angka, atau tanda baca umum."
            return false
        }
        // Character mapping is text-only. Defensively reject navigation/system
        // keys even if a platform KeyCharacterMap returns an unexpected event.
        events.filterNot {
            it.keyCode == KeyEvent.KEYCODE_BACK ||
                it.keyCode == KeyEvent.KEYCODE_APP_SWITCH
        }.forEach { inject(svc, it, id) }
        return true
    }

    fun sendBackspace(count: Int) {
        repeat(count.coerceIn(0, 100)) {
            sendKey(KeyEvent.KEYCODE_DEL, true)
            sendKey(KeyEvent.KEYCODE_DEL, false)
        }
    }

    fun sendStick(right: Boolean, x: Float, y: Float, active: Boolean) {
        val base = if (right) 2 else 0
        axes[base] = x
        axes[base + 1] = y
        val stickId = if (right) "right_stick" else "left_stick"
        val stick = touchMappings.firstOrNull { it.id == stickId }
        if (stick != null) {
            val reachX = displayW * if (right) 0.12f else 0.075f
            val reachY = displayH * if (right) 0.14f else 0.14f
            val px = (stick.x * displayW + x * reachX).coerceIn(0f, displayW.toFloat())
            val py = (stick.y * displayH + y * reachY).coerceIn(0f, displayH.toFloat())
            setMappedTouch("mapped:${stick.id}", px, py, active)
        }
        sendAxes()
    }

    fun setTrigger(left: Boolean, pressed: Boolean) {
        axes[if (left) 4 else 5] = if (pressed) 1f else 0f
        sendKey(if (left) KeyEvent.KEYCODE_BUTTON_L2 else KeyEvent.KEYCODE_BUTTON_R2, pressed)
        sendAxes()
    }

    private fun sendAxes() {
        val svc = service ?: return
        val id = displayId
        if (id < 0) return
        // Joystick axes only matter to gamepad-aware apps, so rate-limit them
        // while always letting the neutral (all-zero) state through.
        val force = axes.all { it == 0f }
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastAxesSentAt < 8L) return
        lastAxesSentAt = now
        if (!force && axes.contentEquals(lastAxesSent)) return
        lastAxesSent = axes.copyOf()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            this.id = 0
            toolType = MotionEvent.TOOL_TYPE_UNKNOWN
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, axes[0])
            setAxisValue(MotionEvent.AXIS_Y, axes[1])
            setAxisValue(MotionEvent.AXIS_Z, axes[2])
            setAxisValue(MotionEvent.AXIS_RZ, axes[3])
            setAxisValue(MotionEvent.AXIS_LTRIGGER, axes[4])
            setAxisValue(MotionEvent.AXIS_BRAKE, axes[4])
            setAxisValue(MotionEvent.AXIS_RTRIGGER, axes[5])
            setAxisValue(MotionEvent.AXIS_GAS, axes[5])
        })
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_MOVE, 1, props, coords,
            0, 0, 1f, 1f, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_JOYSTICK, 0
        )
        inject(svc, ev, id)
        ev.recycle()
    }

    /** Forwards direct preview contacts into the same multi-pointer stream as mapped controls. */
    fun forwardTouch(e: MotionEvent, viewW: Int, viewH: Int): Boolean {
        val svc = service ?: return true
        val id = displayId
        if (id < 0 || pickerOpen) return true
        val target = mappingTargetId
        if (target != null) {
            if (e.actionMasked == MotionEvent.ACTION_UP && viewW > 0 && viewH > 0) {
                setMappingPosition(target, e.x / viewW.toFloat(), e.y / viewH.toFloat())
            }
            return true
        }
        if (displayW <= 0 || displayH <= 0 || viewW <= 0 || viewH <= 0) return true
        synchronized(touchLock) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    releaseDirectTouchesLocked()
                    addTouchLocked(directTouchKey(e.getPointerId(0)),
                        e.getX(0) * displayW / viewW, e.getY(0) * displayH / viewH)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    updateDirectTouchesLocked(e, viewW, viewH)
                    val i = e.actionIndex
                    addTouchLocked(directTouchKey(e.getPointerId(i)),
                        e.getX(i) * displayW / viewW, e.getY(i) * displayH / viewH)
                }
                MotionEvent.ACTION_MOVE -> {
                    updateDirectTouchesLocked(e, viewW, viewH)
                    if (activeTouches.isNotEmpty()) dispatchTouchLocked(MotionEvent.ACTION_MOVE)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    updateDirectTouchesLocked(e, viewW, viewH)
                    removeTouchLocked(directTouchKey(e.getPointerId(e.actionIndex)))
                }
                MotionEvent.ACTION_UP -> removeTouchLocked(directTouchKey(e.getPointerId(0)))
                MotionEvent.ACTION_CANCEL -> releaseDirectTouchesLocked()
            }
        }
        return true
    }

    private fun directTouchKey(pointerId: Int) = "direct:$pointerId"

    private fun updateDirectTouchesLocked(e: MotionEvent, viewW: Int, viewH: Int) {
        for (i in 0 until e.pointerCount) {
            activeTouches[directTouchKey(e.getPointerId(i))]?.let {
                it.x = e.getX(i) * displayW / viewW
                it.y = e.getY(i) * displayH / viewH
            }
        }
    }

    private fun addTouchLocked(key: String, x: Float, y: Float) {
        if (activeTouches.containsKey(key) || activeTouches.size >= 10) return
        val usedIds = activeTouches.values.map { it.id }.toSet()
        val pointerId = (0..31).firstOrNull { it !in usedIds } ?: return
        val wasEmpty = activeTouches.isEmpty()
        val point = ActiveTouch(pointerId, x, y)
        activeTouches[key] = point
        val now = SystemClock.uptimeMillis()
        if (wasEmpty) touchDownTime = now
        val index = activeTouches.size - 1
        dispatchTouchLocked(if (wasEmpty) MotionEvent.ACTION_DOWN else
            MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
    }

    private fun setMappedTouch(key: String, x: Float, y: Float, pressed: Boolean) {
        synchronized(touchLock) {
            if (pressed) {
                val point = activeTouches[key]
                if (point == null) addTouchLocked(key, x, y)
                else {
                    point.x = x
                    point.y = y
                    // Analog sticks and skill wheels fire up to 120 times per
                    // second; coalesce MOVE injection to about 8 ms so the
                    // injected input stream never floods the game.
                    val now = SystemClock.uptimeMillis()
                    if (now - lastMappedMoveAt >= 8L) {
                        lastMappedMoveAt = now
                        dispatchTouchLocked(MotionEvent.ACTION_MOVE)
                    }
                }
            } else {
                removeTouchLocked(key)
            }
        }
    }

    private fun removeTouchLocked(key: String) {
        val index = activeTouches.keys.indexOf(key)
        if (index < 0) return
        val action = if (activeTouches.size == 1) MotionEvent.ACTION_UP else
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        dispatchTouchLocked(action)
        activeTouches.remove(key)
        if (activeTouches.isEmpty()) touchDownTime = 0L
    }

    private fun releaseDirectTouchesLocked() {
        activeTouches.keys.filter { it.startsWith("direct:") }.toList().forEach(::removeTouchLocked)
    }

    private fun releaseAllTouchPointers() {
        synchronized(touchLock) {
            activeTouches.keys.toList().forEach(::removeTouchLocked)
        }
    }

    private fun dispatchTouchLocked(action: Int) {
        val svc = service ?: return
        val id = displayId
        val entries = activeTouches.entries.toList()
        if (id < 0 || entries.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val properties = Array(entries.size) { i ->
            MotionEvent.PointerProperties().apply {
                this.id = entries[i].value.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(entries.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = entries[i].value.x
                y = entries[i].value.y
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            touchDownTime.takeIf { it > 0L } ?: now, now, action, entries.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try { inject(svc, event, id) } finally { event.recycle() }
    }

    private fun inject(svc: IDeckService, ev: android.view.InputEvent, id: Int) {
        // Callers recycle MotionEvents immediately; copy before queuing the Binder transaction.
        val queuedEvent: android.view.InputEvent = when (ev) {
            is MotionEvent -> MotionEvent.obtain(ev)
            is KeyEvent -> KeyEvent(ev)
            else -> return
        }
        runCatching {
            inputWorker.execute {
                try {
                    svc.injectEvent(Bundle().apply { putParcelable("event", queuedEvent) }, id)
                } catch (_: Throwable) {
                    // Shizuku may disconnect while an event is waiting in the queue.
                } finally {
                    (queuedEvent as? MotionEvent)?.recycle()
                }
            }
        }.onFailure { (queuedEvent as? MotionEvent)?.recycle() }
    }

    /** Returns true when the back press was used by the deck. */
    fun handleBack(): Boolean {
        if (pickerOpen && currentApp != null) {
            pickerOpen = false
            return true
        }
        // Ignore host/system Back dispatch so keyboard and IME focus changes
        // cannot be interpreted as virtual-app navigation. The explicit Deck
        // navigation control is the only path that sends KEYCODE_BACK.
        // This prevents key/IME focus changes from becoming accidental app
        // navigation. Back is available only through the explicit Deck control.
        // Keep the host deck in the foreground. Exiting is an explicit action
        // from the assistant bubble, not an accidental Android Back press.
        return true
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 7
        private const val TRACKPAD_TOUCH_KEY = "trackpad_cursor"
        private const val PERFORMANCE_PREFS = "saku_deck_performance"
        private const val PERFORMANCE_RENDER_SCALE = 0.8f
        private const val TRACKPAD_MIN_GAIN = 0.55f
        private const val TRACKPAD_MAX_GAIN = 1.6f
        private const val MAPPING_PREFS = "mobile_legends_touch_mapping"
        private const val CONTROL_LAYOUT_PREFS = "mobile_legends_control_layout"
        private const val CONTROL_SIZE_PREFS = "face_control_sizes"
        private const val MIN_FACE_SIZE_DP = 40f
        private const val MAX_FACE_SIZE_DP = 200f
        private val FACE_CONTROL_IDS = setOf("face_a", "face_b", "face_x", "face_y")
        private val DEFAULT_FACE_CONTROL_SIZES = mapOf(
            "face_a" to 80f,
            "face_b" to 65f,
            "face_x" to 65f,
            "face_y" to 65f,
        )
        private const val CONTROL_VISIBILITY_PREFS = "control_visibility"
        // Virtual display density relative to the phone, so apps fit the smaller center screen.
        private const val DPI_SCALE = 0.75f
        private val DEFAULT_TOUCH_MAPPINGS = listOf(
            TouchMapping("left_stick", "Analog kiri", "LS", null, 0.16f, 0.80f),
            TouchMapping("right_stick", "Analog kanan / kamera", "RS", null, 0.52f, 0.57f),
            TouchMapping("skill_1", "Skill 1 • X", "S1", KeyEvent.KEYCODE_BUTTON_X, 0.78f, 0.72f),
            TouchMapping("skill_2", "Skill 2 • Y", "S2", KeyEvent.KEYCODE_BUTTON_Y, 0.85f, 0.64f),
            TouchMapping("ultimate", "Ultimate • B", "ULT", KeyEvent.KEYCODE_BUTTON_B, 0.91f, 0.71f),
            TouchMapping("attack", "Serang • A", "ATK", KeyEvent.KEYCODE_BUTTON_A, 0.93f, 0.83f),
            TouchMapping("recall", "Recall • Select", "REC", KeyEvent.KEYCODE_BUTTON_SELECT, 0.67f, 0.86f),
            TouchMapping("regen", "Regen • L1", "REG", KeyEvent.KEYCODE_BUTTON_L1, 0.75f, 0.86f),
            TouchMapping("custom_l2", "Sentuh kustom • L2", "L2", KeyEvent.KEYCODE_BUTTON_L2, 0.66f, 0.74f),
            TouchMapping("quick_item", "Item cepat • R1", "R1", KeyEvent.KEYCODE_BUTTON_R1, 0.83f, 0.76f),
            TouchMapping("battle_spell", "Battle Spell • R2", "SP", KeyEvent.KEYCODE_BUTTON_R2, 0.83f, 0.86f),
        )
        private val DEFAULT_CONTROL_POSITIONS = listOf(
            ControlPosition("face_x", 0.50f, 0.17f),
            ControlPosition("face_y", 0.23f, 0.36f),
            ControlPosition("face_a", 0.77f, 0.36f),
            ControlPosition("face_b", 0.50f, 0.55f),
            ControlPosition("extra_spell", 0.91f, 0.58f),
            ControlPosition("extra_regen", 0.78f, 0.88f),
            ControlPosition("extra_recall", 0.94f, 0.86f),
            ControlPosition("right_stick", 0.50f, 0.82f),
        )
    }
}
