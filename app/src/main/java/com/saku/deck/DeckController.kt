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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saku.deck.service.DeckUserService
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, CONNECTING, READY }

data class AppEntry(val label: String, val packageName: String, val component: String)

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
        private set
    var pickerOpen by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var touchMappings by mutableStateOf(loadTouchMappings())
        private set
    var controlPositions by mutableStateOf(loadControlPositions())
        private set
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

    @Volatile
    private var service: IDeckService? = null
    private var binding = false
    private var pendingEnsure = false
    private val worker = Executors.newSingleThreadExecutor()
    // Keep Binder input injection off the Compose/UI thread; preserve event order.
    private val inputWorker = Executors.newSingleThreadExecutor()
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
            main.post {
                binding = false
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
                binding = false
                displayId = -1
                currentApp = null
                pickerOpen = true
                refresh()
            }
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> main.post { refresh() } }
        refresh()
    }

    fun refresh() {
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
        releaseAllTouchPointers()
        surface = null
        val svc = service ?: return
        if (displayId >= 0) worker.execute { runCatching { svc.setSurface(Bundle()) } }
    }

    fun retryDisplay() {
        message = null
        if (service == null) refresh() else ensureDisplay()
    }

    private fun ensureDisplay() {
        val svc = service ?: return
        val s = surface ?: return
        if (surfaceW <= 0 || surfaceH <= 0) return
        if (busy) {
            pendingEnsure = true
            return
        }
        val w = surfaceW and 1.inv()
        val h = surfaceH and 1.inv()
        val dpi = (app.resources.displayMetrics.densityDpi * DPI_SCALE).toInt().coerceAtLeast(120)
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

    fun shutdown() {
        releaseAllTouchPointers()
        val svc = service ?: return
        worker.execute {
            runCatching { svc.releaseDisplay() }
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        }
    }

    // ---- apps ---------------------------------------------------------------

    fun openPicker() {
        pickerOpen = true
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
        val source = when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME -> InputDevice.SOURCE_KEYBOARD
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                InputDevice.SOURCE_DPAD or InputDevice.SOURCE_GAMEPAD
            else -> InputDevice.SOURCE_GAMEPAD
        }
        val ev = KeyEvent(
            downTime, now,
            if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source
        )
        inject(svc, ev, id)
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
        events.forEach { inject(svc, it, id) }
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
        val now = SystemClock.uptimeMillis()
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
        if (!pickerOpen && displayId >= 0) {
            sendKey(KeyEvent.KEYCODE_BACK, true)
            sendKey(KeyEvent.KEYCODE_BACK, false)
            return true
        }
        return false
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 7
        private const val MAPPING_PREFS = "mobile_legends_touch_mapping"
        private const val CONTROL_LAYOUT_PREFS = "mobile_legends_control_layout"
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
