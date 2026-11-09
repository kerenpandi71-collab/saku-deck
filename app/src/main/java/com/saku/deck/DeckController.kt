package com.saku.deck

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Matrix
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

    @Volatile
    private var service: IDeckService? = null
    private var binding = false
    private var pendingEnsure = false
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var surface: Surface? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private var displayW = 0
    private var displayH = 0

    // lx, ly, rx, ry, left trigger, right trigger
    private val axes = FloatArray(6)
    private val keyDownTimes = HashMap<Int, Long>()

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

    // ---- input --------------------------------------------------------------

    fun sendKey(keyCode: Int, down: Boolean) {
        val svc = service ?: return
        val id = displayId
        if (id < 0) return
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

    fun sendStick(right: Boolean, x: Float, y: Float) {
        val base = if (right) 2 else 0
        axes[base] = x
        axes[base + 1] = y
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

    /** Forwards a touch on the center screen to the virtual display. */
    fun forwardTouch(e: MotionEvent, viewW: Int, viewH: Int): Boolean {
        val svc = service ?: return true
        val id = displayId
        if (id < 0 || pickerOpen) return true
        val copy = MotionEvent.obtain(e)
        if (displayW > 0 && viewW > 0 && viewH > 0 && (displayW != viewW || displayH != viewH)) {
            copy.transform(Matrix().apply {
                setScale(displayW / viewW.toFloat(), displayH / viewH.toFloat())
            })
        }
        inject(svc, copy, id)
        copy.recycle()
        return true
    }

    private fun inject(svc: IDeckService, ev: android.view.InputEvent, id: Int) {
        runCatching { svc.injectEvent(Bundle().apply { putParcelable("event", ev) }, id) }
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
        // Virtual display density relative to the phone, so apps fit the smaller center screen.
        private const val DPI_SCALE = 0.75f
    }
}
