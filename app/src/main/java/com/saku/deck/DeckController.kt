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
import java.io.File
import java.util.ArrayDeque
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
    var recentTasks by mutableStateOf<List<RecentTaskEntry>>(loadRecentApps())
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
    var logDialogOpen by mutableStateOf(false)
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
    // Dedicated Mobile Legends layout: analog left, skill cluster right, screen centered.
    // Defaults to ON because this branch is the ML-specialized build.
    var mlOnlyMode by mutableStateOf(loadMlOnlyMode())
        private set
    var virtualFps by mutableIntStateOf(0)
        private set

    // Blue aim indicator mirrors Virtual Display Touch: it follows the active
    // skill-wheel contact in virtual-display normalized coordinates.
    var skillAimActive by mutableStateOf(false)
        private set
    var skillAimX by mutableFloatStateOf(0.5f)
        private set
    var skillAimY by mutableFloatStateOf(0.5f)
        private set

    // Cursor position for the Titan trackpad, as a 0..1 fraction of the virtual
    // display size. Stays negative until the trackpad is first used. The host UI
    // draws its own pointer sprite at this position: Android only renders the
    // native mouse cursor for real mouse devices, never for injected events.
    var trackpadCursorX by mutableFloatStateOf(-1f)
        private set
    var trackpadCursorY by mutableFloatStateOf(-1f)
        private set

    var landscapeResolutionDialogOpen by mutableStateOf(false)
    var landscapeResolutionEnabled by mutableStateOf(loadLandscapeResolutionEnabled())
        private set
    var landscapeWidth by mutableIntStateOf(loadLandscapeWidth())
        private set
    var landscapeHeight by mutableIntStateOf(loadLandscapeHeight())
        private set
    var landscapeDpi by mutableIntStateOf(loadLandscapeDpi())
        private set
    // Physical bezel/frame sizing for Landscape. This changes the host UI
    // geometry only; it does not increase the virtual display render resolution.
    var landscapeFrameWidth by mutableFloatStateOf(loadLandscapeFrameWidth())
        private set
    var landscapeFrameHeight by mutableFloatStateOf(loadLandscapeFrameHeight())
        private set
    var landscapeFrameDialogOpen by mutableStateOf(false)

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

    fun toggleMlOnlyMode() {
        mlOnlyMode = !mlOnlyMode
        app.getSharedPreferences("saku_deck_display_mode", Context.MODE_PRIVATE)
            .edit().putBoolean("ml_only", mlOnlyMode).apply()
        assistantMenuOpen = false
        message = if (mlOnlyMode) "Mode ML aktif: layar kotak 720×720 di tengah."
        else "Mode gamepad lengkap aktif."
        // Recreate/resize the virtual display to the square ML geometry.
        ensureDisplay()
    }

    fun applyLandscapeResolution(width: Int, height: Int, dpi: Int) {
        if (width !in 640..3840 || height !in 360..2160 || width <= height || dpi !in 120..640) {
            message = "Resolusi Landscape harus 640–3840 × 360–2160, lebar lebih besar dari tinggi, DPI 120–640."
            return
        }
        landscapeWidth = width
        landscapeHeight = height
        landscapeDpi = dpi
        landscapeResolutionEnabled = true
        app.getSharedPreferences(LANDSCAPE_RESOLUTION_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true)
            .putInt("width", width)
            .putInt("height", height)
            .putInt("dpi", dpi)
            .apply()
        landscapeResolutionDialogOpen = false
        message = "Resolusi custom Landscape diterapkan: ${width}×${height} @ ${dpi} DPI."
        ensureDisplay()
    }

    fun disableLandscapeResolution() {
        landscapeResolutionEnabled = false
        app.getSharedPreferences(LANDSCAPE_RESOLUTION_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", false).apply()
        landscapeResolutionDialogOpen = false
        message = "Resolusi Landscape kembali mengikuti ukuran layar otomatis."
        ensureDisplay()
    }

    fun setLandscapeFrameSize(width: Float, height: Float) {
        landscapeFrameWidth = width.coerceIn(0.2f, 1.3f)
        landscapeFrameHeight = height.coerceIn(0.65f, 1f)
        app.getSharedPreferences(LANDSCAPE_FRAME_PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("width", landscapeFrameWidth)
            .putFloat("height", landscapeFrameHeight)
            .apply()
    }

    private fun loadLandscapeFrameWidth(): Float =
        app.getSharedPreferences(LANDSCAPE_FRAME_PREFS, Context.MODE_PRIVATE)
            .getFloat("width", 1f).coerceIn(0.2f, 1.3f)

    private fun loadLandscapeFrameHeight(): Float =
        app.getSharedPreferences(LANDSCAPE_FRAME_PREFS, Context.MODE_PRIVATE)
            .getFloat("height", 1f).coerceIn(0.65f, 1f)


    private fun loadLandscapeResolutionEnabled(): Boolean {
        val prefs = app.getSharedPreferences(LANDSCAPE_RESOLUTION_PREFS, Context.MODE_PRIVATE)
        // Migrate older installs from the former automatic/full-surface default
        // to the requested 540p Landscape default, while retaining saved custom
        // dimensions when the user had already enabled them.
        if (!prefs.getBoolean("default_540p_migrated", false)) {
            val editor = prefs.edit().putBoolean("default_540p_migrated", true)
            if (!prefs.getBoolean("enabled", false)) {
                editor.putBoolean("enabled", true).putInt("width", 960).putInt("height", 540)
            }
            editor.apply()
        }
        return prefs.getBoolean("enabled", true)
    }

    private fun loadLandscapeWidth(): Int =
        app.getSharedPreferences(LANDSCAPE_RESOLUTION_PREFS, Context.MODE_PRIVATE)
            .getInt("width", 960)

    private fun loadLandscapeHeight(): Int =
        app.getSharedPreferences(LANDSCAPE_RESOLUTION_PREFS, Context.MODE_PRIVATE)
            .getInt("height", 540)

    private fun loadLandscapeDpi(): Int =
        app.getSharedPreferences(LANDSCAPE_RESOLUTION_PREFS, Context.MODE_PRIVATE)
            .getInt("dpi", 240)

    private fun loadTitanPortraitMode(): Boolean =
        app.getSharedPreferences("saku_deck_display_mode", Context.MODE_PRIVATE)
            .getBoolean("titan_portrait", false)

    private fun loadMlOnlyMode(): Boolean =
        app.getSharedPreferences("saku_deck_display_mode", Context.MODE_PRIVATE)
            .getBoolean("ml_only", true)

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
    // Keep only the newest pending MOVE between transition events. DOWN/UP and key events are never dropped.
    private data class PendingInput(val service: IDeckService, val event: android.view.InputEvent, val displayId: Int)
    private val inputQueueLock = Any()
    private val pendingInput = ArrayDeque<PendingInput>()
    private var inputDrainScheduled = false
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
    private var displayDpi = 0

    private val keyDownTimes = HashMap<Int, Long>()
    @Volatile private var lastTouchMoveEnqueueAt = 0L
    private val scrollAccumLock = Any()
    private var pendingScrollH = 0f
    private var pendingScrollV = 0f
    private var lastScrollSendAt = 0L
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
            if (binder == null || !binder.pingBinder()) {
                handleShizukuServiceLost("Binder Shizuku tidak valid; mencoba menyambungkan ulang.")
                return
            }
            val connected = runCatching { IDeckService.Stub.asInterface(binder) }.getOrNull()
            if (connected == null || !runCatching { connected.asBinder().isBinderAlive }.getOrDefault(false)) {
                handleShizukuServiceLost("Binder layanan Saku Deck tidak aktif; mencoba menyambungkan ulang.")
                return
            }
            service = connected
            main.post {
                binding = false
                shizukuState = ShizukuState.READY
                message = null
                ensureDisplay()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleShizukuServiceLost("Koneksi layanan Shizuku terputus.")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleShizukuServiceLost("Binding layanan Shizuku mati; menyambungkan ulang.")
            runCatching { Shizuku.unbindUserService(userServiceArgs, this, true) }
        }

        override fun onNullBinding(name: ComponentName?) {
            handleShizukuServiceLost("Layanan Shizuku mengembalikan Binder kosong.")
            runCatching { Shizuku.unbindUserService(userServiceArgs, this, true) }
        }
    }

    private fun handleShizukuServiceLost(reason: String) {
        service = null
        fpsCapturePackage = null
        fpsLastValidAtMs = 0L
        main.post {
            binding = false
            virtualFps = 0
            displayId = -1
            displayW = 0
            displayH = 0
            displayDpi = 0
            currentApp = null
            pickerOpen = true
            shizukuState = ShizukuState.CONNECTING
            message = reason
            scheduleShizukuReconnect()
        }
    }

    private fun scheduleShizukuReconnect() {
        main.postDelayed({
            if (hostActive && !shuttingDown && service == null && !binding) refresh()
        }, 650L)
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
                displayW = 0
                displayH = 0
                displayDpi = 0
                currentApp = null
                virtualFps = 0
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
        val connectedService = service
        if (connectedService != null && runCatching { connectedService.asBinder().isBinderAlive }.getOrDefault(false)) {
            shizukuState = ShizukuState.READY
            return
        }
        // A stale AIDL proxy must not keep the UI in READY or reuse an ID
        // that belonged to the dead helper process.
        if (connectedService != null) {
            service = null
            binding = false
            displayId = -1
            displayW = 0
            displayH = 0
            displayDpi = 0
            currentApp = null
            virtualFps = 0
            pickerOpen = true
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
            // Some Shizuku builds never deliver onServiceConnected after
            // unbindUserService ran on the same connection. That is what
            // left the deck stuck on "Menghubungkan ke Shizuku" after
            // KELUAR. If the service is still missing after 4 s, retry.
            main.postDelayed({
                if (binding && service == null && hostActive && !shuttingDown) {
                    binding = false
                    refresh()
                }
            }, 4000L)
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
        // Landscape defaults to 960×540 (540p); portrait continues using
        // the live host surface dimensions without inheriting Landscape settings.
        val useLandscapeCustom = surfaceW > surfaceH && landscapeResolutionEnabled
        // ML mode always renders a square 720×720 deck centered between the
        // control panels; the square UI surface matches this exactly.
        val useMlSquare = mlOnlyMode
        val w = (if (useMlSquare) ML_SQUARE_SIDE
            else if (useLandscapeCustom) landscapeWidth else surfaceW).coerceAtLeast(64)
        val h = (if (useMlSquare) ML_SQUARE_SIDE
            else if (useLandscapeCustom) landscapeHeight else surfaceH).coerceAtLeast(64)
        val dpi = (if (useLandscapeCustom) landscapeDpi
            else (app.resources.displayMetrics.densityDpi * DPI_SCALE).toInt()).coerceAtLeast(120)
        val existing = displayId
        busy = true
        worker.execute {
            val result = runCatching {
                if (existing >= 0) {
                    svc.setSurface(Bundle().apply { putParcelable("surface", s) })
                    if (w != displayW || h != displayH || dpi != displayDpi) {
                        val resized = svc.resizeDisplay(w, h, dpi)
                        if (resized.getInt("displayId", -1) < 0) {
                            throw IllegalStateException(resized.getString("error") ?: "Virtual display gagal diubah ukurannya")
                        }
                    }
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
                        displayDpi = dpi
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
        displayDpi = 0
        currentApp = null
        virtualFps = 0
        fpsCapturePackage = null
        fpsLastValidAtMs = 0L
        pickerOpen = true
        recentsOpen = false
        recentTasks = emptyList()
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
            val active = raw.lineSequence().mapNotNull { line ->
                val parts = line.split(" • ")
                val taskId = parts.firstOrNull()?.removePrefix("taskId=")?.toIntOrNull()
                    ?: return@mapNotNull null
                val component = parts.getOrNull(1) ?: return@mapNotNull null
                RecentTaskEntry(taskId, resolveRecentTaskLabel(component), component)
            }.distinctBy { it.component }.toList()
            val saved = loadRecentApps()
            val merged = (active + saved.filterNot { old -> active.any { it.component == old.component } })
                .distinctBy { it.component }.take(50)
            persistRecentApps(merged)
            main.post {
                if (displayId != targetDisplay || service !== svc) return@post
                recentTasks = merged
                recentsOpen = true
                pickerOpen = false
            }
        }
    }

    private fun loadRecentApps(): List<RecentTaskEntry> {
        val prefs = app.getSharedPreferences(RECENT_APPS_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(RECENT_APPS_KEY, "").orEmpty().lineSequence().mapNotNull { line ->
            val component = line.substringAfter('|', "")
            if (component.isBlank()) return@mapNotNull null
            val label = line.substringBefore('|').ifBlank { resolveRecentTaskLabel(component) }
            RecentTaskEntry(-1, label, component)
        }.distinctBy { it.component }.take(50).toList()
    }

    private fun persistRecentApps(entries: List<RecentTaskEntry>) {
        val serialized = entries.take(50).joinToString("\n") { "${it.label}|${it.component}" }
        app.getSharedPreferences(RECENT_APPS_PREFS, Context.MODE_PRIVATE).edit()
            .putString(RECENT_APPS_KEY, serialized).apply()
    }

    private fun rememberRecentApp(entry: AppEntry) {
        val existing = loadRecentApps().filterNot { it.component == entry.component }
        val updated = (listOf(RecentTaskEntry(-1, entry.label, entry.component)) + existing).take(50)
        persistRecentApps(updated)
        recentTasks = updated
    }

    fun focusRecentTask(entry: RecentTaskEntry) {
        val svc = service ?: return
        val targetDisplay = displayId
        if (targetDisplay < 0) return
        worker.execute {
            val result = if (entry.taskId > 0) {
                runCatching { svc.focusOpenTask(targetDisplay, entry.taskId) }
                    .getOrElse { "Task tidak bisa difokuskan: ${it.message}" }
            } else {
                runCatching { svc.launchApp(entry.component, targetDisplay) }
                    .getOrElse { "Gagal membuka aplikasi: ${it.message}" }
            }
            main.post {
                if (displayId != targetDisplay || service !== svc) return@post
                recentsOpen = false
                pickerOpen = false
                currentApp = AppEntry(entry.label, entry.component.substringBefore('/'), entry.component)
                message = result.takeIf { it.contains("Gagal", true) || it.contains("Error", true) }
            }
        }
    }

    fun closeRecentTask(entry: RecentTaskEntry) {
        if (entry.taskId <= 0) {
            val remaining = loadRecentApps().filterNot { it.component == entry.component }
            persistRecentApps(remaining)
            recentTasks = recentTasks.filterNot { it.component == entry.component }
            return
        }
        val svc = service ?: return
        val targetDisplay = displayId
        if (targetDisplay < 0) return
        worker.execute {
            val result = runCatching { svc.closeOpenTask(targetDisplay, entry.taskId) }
                .getOrElse { "Gagal menutup aplikasi: ${it.message}" }
            val refreshed = runCatching { svc.getOpenTasks(targetDisplay) }.getOrDefault("")
            val active = refreshed.lineSequence().mapNotNull { line ->
                val parts = line.split(" • ")
                val taskId = parts.firstOrNull()?.removePrefix("taskId=")?.toIntOrNull()
                    ?: return@mapNotNull null
                val component = parts.getOrNull(1) ?: return@mapNotNull null
                RecentTaskEntry(taskId, resolveRecentTaskLabel(component), component)
            }.distinctBy { it.component }.toList()
            val saved = loadRecentApps()
            val entries = (active + saved.filterNot { old -> active.any { it.component == old.component } })
                .distinctBy { it.component }.take(50)
            persistRecentApps(entries)
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
        rememberRecentApp(entry)
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

    private val logFile by lazy { File(app.filesDir, "saku_log.txt") }

    fun errorLog(): String = runCatching {
        logFile.takeIf { it.exists() }?.readText()
    }.getOrNull()?.trim().takeIf { !it.isNullOrEmpty() } ?: "Belum ada log error."

    fun clearErrorLog() {
        runCatching { logFile.delete() }
    }

    fun updateExternalKeyboardOpen(open: Boolean) {
        externalKeyboardOpen = open
    }

    private var mouseCursorX = 0f
    private var mouseCursorY = 0f
    private var mouseCursorInitialized = false
    private var mouseDownTime = 0L
    private var lastHoverSentAt = 0L
    private var lastCursorSpriteAt = 0L
    private var trackpadContactActive = false
    private var tapRelease: Runnable? = null
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
        // Throttle the on-screen cursor SPRITE to ~30 fps. Writing the
        // state on every pointer event redrew the whole host window up to
        // 120 times per second, starving the game of GPU/CPU time on
        // low-end devices. Injection always uses the exact position.
        val spriteNow = SystemClock.uptimeMillis()
        if (spriteNow - lastCursorSpriteAt >= 33L) {
            lastCursorSpriteAt = spriteNow
            trackpadCursorX = mouseCursorX / displayW
            trackpadCursorY = mouseCursorY / displayH
        }
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
        // The gesture loop fires at raw pointer-event rate (up to 120Hz). Throttle
        // to ~60Hz and SUM the skipped deltas so total scroll distance is exact —
        // dropping events slowed scrolling and flooding the queue made it stutter.
        val now = SystemClock.uptimeMillis()
        synchronized(scrollAccumLock) {
            pendingScrollH += horizontal
            pendingScrollV += vertical
            if (now - lastScrollSendAt < 16L) return
            lastScrollSendAt = now
        }
        val sendH: Float
        val sendV: Float
        synchronized(scrollAccumLock) {
            sendH = pendingScrollH
            sendV = pendingScrollV
            pendingScrollH = 0f
            pendingScrollV = 0f
        }
        if (sendH == 0f && sendV == 0f) return
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            this.id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = mouseCursorX
            y = mouseCursorY
            setAxisValue(MotionEvent.AXIS_HSCROLL, sendH)
            setAxisValue(MotionEvent.AXIS_VSCROLL, sendV)
        })
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
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH || keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_ESCAPE) return
        sendKeyInternal(keyCode, down)
    }

    /** Sends Back only for an explicit navigation control, never for text-key input. */
    fun sendNavigationBack(down: Boolean) {
        if (!down) {
            sendKeyInternal(KeyEvent.KEYCODE_BACK, false)
            worker.execute {
                Thread.sleep(420)
                val svc = service ?: return@execute
                val targetDisplay = displayId
                if (targetDisplay < 0) return@execute
                val active = runCatching { svc.getOpenTasks(targetDisplay) }.getOrDefault("")
                if (active.isBlank()) main.post {
                    if (displayId == targetDisplay && service === svc) {
                        currentApp = null
                        pickerOpen = true
                        recentsOpen = false
                    }
                }
            }
            return
        }
        sendKeyInternal(KeyEvent.KEYCODE_BACK, true)
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
        if (keyCode !in setOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V)) return
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
        if (pressed && displayW > 0 && displayH > 0) {
            skillAimX = (x / displayW).coerceIn(0f, 1f)
            skillAimY = (y / displayH).coerceIn(0f, 1f)
            skillAimActive = true
        } else {
            skillAimActive = false
        }
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
                it.keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
                it.keyCode == KeyEvent.KEYCODE_HOME ||
                it.keyCode == KeyEvent.KEYCODE_MENU ||
                it.keyCode == KeyEvent.KEYCODE_ESCAPE
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
        val stickId = if (right) "right_stick" else "left_stick"
        val stick = touchMappings.firstOrNull { it.id == stickId }
        if (stick != null) {
            val reachX = displayW * if (right) 0.12f else 0.075f
            val reachY = displayH * 0.14f
            val px = (stick.x * displayW + x * reachX).coerceIn(0f, displayW.toFloat())
            val py = (stick.y * displayH + y * reachY).coerceIn(0f, displayH.toFloat())
            setMappedTouch("mapped:${stick.id}", px, py, active)
        }
    }

    fun setTrigger(left: Boolean, pressed: Boolean) {
        // Trigger controls share the mapped touchscreen stream with the other buttons.
        sendKey(if (left) KeyEvent.KEYCODE_BUTTON_L2 else KeyEvent.KEYCODE_BUTTON_R2, pressed)
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
                    // dispatchTouchLocked applies one shared 60 Hz limit for all mapped and direct contacts.
                    // The input queue then replaces stale pending MOVE samples without crossing DOWN/UP barriers.
                    dispatchTouchLocked(MotionEvent.ACTION_MOVE)
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
        // Keep analog updates near the game's 60 Hz frame cadence. This
        // reduces Binder/InputManager work and prevents input traffic from
        // competing with MLBB's render thread. DOWN/UP transitions stay lossless.
        if (action == MotionEvent.ACTION_MOVE) {
            if (now - lastTouchMoveEnqueueAt < 16L) return
            lastTouchMoveEnqueueAt = now
        }
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
        val item = PendingInput(svc, queuedEvent, id)
        synchronized(inputQueueLock) {
            // Consecutive MOVE/SCROLL events describe the same active stream. Keep
            // the newest; never replace across a DOWN/UP or key barrier. Without
            // this, a slow drain turns scroll gestures into a stuttering backlog.
            val last = pendingInput.peekLast()
            val streamAction = (queuedEvent as? MotionEvent)?.actionMasked
                ?.takeIf { it == MotionEvent.ACTION_MOVE || it == MotionEvent.ACTION_SCROLL }
            val replaceMove = streamAction != null &&
                last?.event is MotionEvent &&
                (last.event as MotionEvent).actionMasked == streamAction &&
                last.displayId == id
            if (replaceMove) {
                recycleInput(pendingInput.removeLast().event)
            }
            pendingInput.addLast(item)
            if (!inputDrainScheduled) {
                inputDrainScheduled = true
                runCatching { inputWorker.execute { drainInputQueue() } }
                    .onFailure {
                        inputDrainScheduled = false
                        while (pendingInput.isNotEmpty()) recycleInput(pendingInput.removeFirst().event)
                    }
            }
        }
    }

    private fun recycleInput(event: android.view.InputEvent) {
        (event as? MotionEvent)?.recycle()
    }

    private fun drainInputQueue() {
        while (true) {
            val item = synchronized(inputQueueLock) {
                if (pendingInput.isEmpty()) {
                    inputDrainScheduled = false
                    null
                } else {
                    pendingInput.removeFirst()
                }
            } ?: return
            try {
                item.service.injectEvent(
                    Bundle().apply { putParcelable("event", item.event) },
                    item.displayId,
                )
            } catch (_: Throwable) {
                // Shizuku may disconnect while queued input is being drained.
            } finally {
                recycleInput(item.event)
            }
        }
    }

    /** Returns true when the back press was used by the deck. */
    fun handleBack(): Boolean {
        if (recentsOpen) {
            recentsOpen = false
            return true
        }
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
        private const val LANDSCAPE_RESOLUTION_PREFS = "saku_deck_landscape_resolution"
        private const val LANDSCAPE_FRAME_PREFS = "saku_deck_landscape_frame"
        private const val TRACKPAD_MIN_GAIN = 0.8f
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
        private const val RECENT_APPS_PREFS = "saku_deck_recent_apps"
        private const val RECENT_APPS_KEY = "components"
        // Virtual display density relative to the phone, so apps fit the smaller center screen.
        private const val DPI_SCALE = 0.75f
        private const val ML_SQUARE_SIDE = 720
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
            ControlPosition("left_stick", 0.50f, 0.50f),
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
