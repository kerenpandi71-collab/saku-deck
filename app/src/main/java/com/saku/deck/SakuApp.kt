package com.saku.deck

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SakuApp : Application() {
    lateinit var controller: DeckController
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLog()
        controller = DeckController(this)
        controller.init()
    }

    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                val trace = throwable.stackTrace.take(14).joinToString("\n") { "  at $it" }
                File(filesDir, "saku_log.txt")
                    .appendText("$time CRASH di ${thread.name}: ${throwable.javaClass.name}: ${throwable.message}\n$trace\n")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
