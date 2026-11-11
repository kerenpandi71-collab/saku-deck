package com.saku.deck.service

import android.hardware.display.VirtualDisplayConfig
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import com.saku.deck.IDeckService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Runs in a separate process started by Shizuku with shell (or root) identity.
 * It owns the virtual display, launches apps on it and injects input into it.
 */
class DeckUserService : IDeckService.Stub() {

    private val lock = Any()
    private var callback: Any? = null
    private var displayId = -1
    private var navigationToken: Binder? = null
    private val navigationPackage = "com.android.shell"
    private var lastInputError = "Belum ada event input yang diuji."
    private var lastInputLogAt = 0L

    init {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }

    // ---- reflection helpers -------------------------------------------------

    private fun systemService(name: String): IBinder =
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder

    private fun asInterface(iface: String, binder: IBinder): Any =
        Class.forName("$iface\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!

    private fun ifaceMethod(iface: String, name: String, argc: Int): Method =
        Class.forName(iface).methods.first { it.name == name && it.parameterTypes.size == argc }

    private val displayManager: Any by lazy {
        asInterface(DISPLAY_MANAGER, systemService("display"))
    }
    private val inputManager: Any by lazy {
        asInterface(INPUT_MANAGER, systemService("input"))
    }
    private val createMethod by lazy { ifaceMethod(DISPLAY_MANAGER, "createVirtualDisplay", 4) }
    private val setSurfaceMethod by lazy { ifaceMethod(DISPLAY_MANAGER, "setVirtualDisplaySurface", 2) }
    private val resizeMethod by lazy { ifaceMethod(DISPLAY_MANAGER, "resizeVirtualDisplay", 4) }
    private val releaseMethod by lazy { ifaceMethod(DISPLAY_MANAGER, "releaseVirtualDisplay", 1) }
    private val injectMethod by lazy { ifaceMethod(INPUT_MANAGER, "injectInputEvent", 2) }
    private val setDisplayIdMethod by lazy {
        InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }
    }

    private val windowManager: Any by lazy {
        asInterface(WINDOW_MANAGER, systemService("window"))
    }
    private val setDisplayImePolicyMethod: Method? by lazy {
        runCatching {
            Class.forName("android.view.IWindowManager")
                .getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    /**
     * On Android 11-13 the VirtualDisplayConfig builder has no setImePolicy(),
     * so the soft keyboard still opens inside the virtual display. The reliable
     * fix on those versions is IWindowManager.setDisplayImePolicy(displayId,
     * DISPLAY_IME_POLICY_HIDE = 2), available since Android 12; the display must
     * be trusted, which the first flag set already provides. The host activity
     * keeps owning text input through its IME bridge.
     */
    private fun applyImeHidePolicy(displayId: Int) {
        val method = setDisplayImePolicyMethod ?: return
        runCatching { method.invoke(windowManager, displayId, 2) }
            .onFailure { Log.w("SakuDeck", "setDisplayImePolicy(HIDE) gagal", unwrap(it)) }
    }

    private fun unwrap(t: Throwable): Throwable =
        (t as? InvocationTargetException)?.targetException ?: t

    private fun requestHideVirtualDisplayIme(builder: VirtualDisplayConfig.Builder) {
        runCatching {
            // The virtual app display must never spawn a second keyboard. Text
            // entry is handled by the host Activity's Android IME bridge.
            val policy = runCatching {
                VirtualDisplayConfig::class.java.getDeclaredField("IME_POLICY_HIDE").apply {
                    isAccessible = true
                }.getInt(null)
            }.getOrDefault(2)
            val method = builder.javaClass.methods.firstOrNull {
                it.name == "setImePolicy" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return
            method.invoke(builder, policy)
        }.onFailure { Log.w("SakuDeck", "Virtual display IME hide policy unavailable", unwrap(it)) }
    }

    // ---- Android navigation guard (MagicDesk-style) ------------------------

    /**
     * Disable Home and Overview through SystemUI, not by hiding this Activity
     * from Recents. The Binder token is owned by this UserService: SystemUI
     * automatically drops the flags if the helper process dies.
     */
    private fun setNavigationGuard(enabled: Boolean) {
        val statusBar = asInterface(STATUS_BAR_SERVICE, systemService("statusbar"))
        val token = navigationToken ?: Binder().also { navigationToken = it }
        val flags = if (enabled) DISABLE_HOME or DISABLE_RECENT else 0
        val userId = Process.myUid() / 100000
        try {
            // Shizuku UserService executes as shell (uid 2000), not as the app.
            // SystemUI validates the package attribution against the caller UID.
            val attributedPackage = "com.android.shell"
            val methods = statusBar.javaClass.methods
            val disableForUser = methods.firstOrNull {
                it.name == "disableForUser" && it.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, IBinder::class.java, String::class.java, Int::class.javaPrimitiveType)
                )
            }
            val disable = methods.firstOrNull {
                it.name == "disable" && it.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                )
            }

            // Prefer the per-user API; fall back to the original MagicDesk-style
            // disable() transaction on ROMs whose IStatusBarService differs.
            if (disableForUser != null) {
                runCatching { disableForUser.invoke(statusBar, flags, token, attributedPackage, userId) }
                    .onFailure {
                        if (disable == null) throw unwrap(it)
                        disable.invoke(statusBar, flags, token, attributedPackage)
                    }
            } else {
                (disable ?: throw NoSuchMethodException("IStatusBarService.disable/disableForUser"))
                    .invoke(statusBar, flags, token, attributedPackage)
            }
            if (!enabled) navigationToken = null
            Log.i("SakuDeckNavigation", if (enabled) "Home/Overview disabled for deck session" else "Home/Overview restored")
        } catch (t: Throwable) {
            if (enabled) navigationToken = null
            throw unwrap(t)
        }
    }

    // ---- display ------------------------------------------------------------

    override fun createDisplay(args: Bundle): Bundle {
        val out = Bundle()
        synchronized(lock) {
            try {
                args.classLoader = Surface::class.java.classLoader
                val surface = args.getParcelable("surface", Surface::class.java)
                val width = args.getInt("width")
                val height = args.getInt("height")
                val dpi = args.getInt("dpi")
                releaseLocked()

                var lastError: Throwable? = null
                for (flags in FLAG_SETS) {
                    try {
                        val builder = VirtualDisplayConfig.Builder("SakuDeck", width, height, dpi)
                            .setFlags(flags)
                        requestHideVirtualDisplayIme(builder)
                        if (surface != null) builder.setSurface(surface)
                        val cb = asInterface(DISPLAY_CALLBACK, Binder())
                        val id = createMethod.invoke(
                            displayManager, builder.build(), cb, null, "com.android.shell"
                        ) as Int
                        if (id >= 0) {
                            callback = cb
                            displayId = id
                            applyImeHidePolicy(id)
                            runCatching { setNavigationGuard(true) }
                                .onFailure { Log.w("SakuDeckNavigation", "Home/Overview guard unavailable", unwrap(it)) }
                            out.putInt("displayId", id)
                            out.putInt("flags", flags)
                            return out
                        }
                    } catch (t: Throwable) {
                        lastError = unwrap(t)
                    }
                }
                out.putString("error", lastError?.toString() ?: "unknown error")
            } catch (t: Throwable) {
                out.putString("error", unwrap(t).toString())
            }
        }
        return out
    }

    override fun setSurface(args: Bundle): Bundle {
        val out = Bundle()
        synchronized(lock) {
            val cb = callback
            if (cb == null) {
                out.putString("error", "no display")
                return out
            }
            try {
                args.classLoader = Surface::class.java.classLoader
                val surface = args.getParcelable("surface", Surface::class.java)
                setSurfaceMethod.invoke(displayManager, cb, surface)
                out.putInt("displayId", displayId)
            } catch (t: Throwable) {
                out.putString("error", unwrap(t).toString())
            }
        }
        return out
    }

    override fun resizeDisplay(width: Int, height: Int, dpi: Int): Bundle {
        val out = Bundle()
        synchronized(lock) {
            val cb = callback
            if (cb == null) {
                out.putString("error", "no display")
                return out
            }
            try {
                resizeMethod.invoke(displayManager, cb, width, height, dpi)
                out.putInt("displayId", displayId)
            } catch (t: Throwable) {
                out.putString("error", unwrap(t).toString())
            }
        }
        return out
    }

    override fun releaseDisplay() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        // Always restore navigation before releasing the virtual display.
        runCatching { setNavigationGuard(false) }
            .onFailure { Log.w("SakuDeckNavigation", "Navigation restore failed", unwrap(it)) }
        val cb = callback ?: run {
            displayId = -1
            return
        }
        try {
            releaseMethod.invoke(displayManager, cb)
        } catch (t: Throwable) {
            Log.e("SakuDeckLifecycle", "Virtual display release failed (id=$displayId)", unwrap(t))
        } finally {
            callback = null
            displayId = -1
        }
    }

    // ---- apps & input -------------------------------------------------------

    override fun launchApp(component: String, displayId: Int): String {
        val base = listOf(
            "/system/bin/cmd", "activity", "start-activity",
            "--display", displayId.toString(),
            "--windowingMode", "1",
        )
        // First try a normal new task; if the app is already open elsewhere, force a new task here.
        val first = exec(base + listOf("-f", "0x10000000", "-n", component))
        if (first.contains("brought to the front", ignoreCase = true) ||
            first.contains("current task", ignoreCase = true)
        ) {
            return exec(base + listOf("-f", "0x18000000", "-n", component))
        }
        return first
    }

    @Synchronized
    override fun injectEvent(event: Bundle, displayId: Int) {
        var input: InputEvent? = null
        try {
            event.classLoader = InputEvent::class.java.classLoader
            input = event.getParcelable("event", InputEvent::class.java) ?: return
            setDisplayIdMethod.invoke(input, displayId)
            val accepted = injectMethod.invoke(inputManager, input, 0) as? Boolean ?: false
            lastInputError = if (accepted) "Event diterima oleh InputManager pada display $displayId."
            else "InputManager menolak event untuk display $displayId."
            if (!accepted) logInputFailure(lastInputError)
        } catch (t: Throwable) {
            lastInputError = "${unwrap(t).javaClass.simpleName}: ${unwrap(t).message ?: "injeksi input gagal"}"
            logInputFailure(lastInputError, unwrap(t))
        } finally {
            (input as? android.view.MotionEvent)?.recycle()
        }
    }

    @Synchronized
    override fun getInputStatus(): String = lastInputError

    private fun logInputFailure(message: String, error: Throwable? = null) {
        val now = SystemClock.uptimeMillis()
        if (now - lastInputLogAt < 1_000L) return
        lastInputLogAt = now
        if (error == null) Log.e("SakuDeckInput", message)
        else Log.e("SakuDeckInput", message, error)
    }

    override fun runShell(command: String): String = exec(listOf("/system/bin/sh", "-c", command))

    override fun getUid(): Int = Process.myUid()

    override fun destroy() {
        releaseDisplay()
        exitProcess(0)
    }

    private fun exec(cmd: List<String>): String {
        var process: Process? = null
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process = p
            val output = StringBuilder()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().use { output.append(it.readText()) } }
            }.apply { isDaemon = true; name = "SakuDeckShellReader"; start() }

            if (!p.waitFor(2200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                reader.join(150)
                return "Error: command timed out after 2200ms"
            }
            reader.join(250)
            output.toString()
        } catch (t: Throwable) {
            process?.destroyForcibly()
            "Error: $t"
        }
    }

    companion object {
        private const val DISPLAY_MANAGER = "android.hardware.display.IDisplayManager"
        private const val DISPLAY_CALLBACK = "android.hardware.display.IVirtualDisplayCallback"
        private const val INPUT_MANAGER = "android.hardware.input.IInputManager"
        private const val WINDOW_MANAGER = "android.view.IWindowManager"
        private const val STATUS_BAR_SERVICE = "com.android.internal.statusbar.IStatusBarService"
        private const val DISABLE_HOME = 0x00200000
        private const val DISABLE_RECENT = 0x01000000

        // DisplayManager virtual display flags (some are hidden in the SDK).
        private const val PUBLIC = 1
        private const val PRESENTATION = 1 shl 1
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15

        private const val BASE = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or
            SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL

        // Tried in order; later sets drop flags some ROMs refuse.
        // Avoid OWN_FOCUS and DEVICE_DISPLAY_GROUP: both alter system focus/display
        // grouping and are unnecessary for a touch-capable app-hosting display.
        private val FLAG_SETS = intArrayOf(
            BASE or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED,
            BASE or TRUSTED,
            BASE,
        )
    }
}
