package com.saku.deck

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.saku.deck.ui.ConsoleScreen
import com.saku.deck.ui.SakuTheme

class MainActivity : ComponentActivity() {

    private val controller: DeckController
        get() = (application as SakuApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back is routed to the focused app on the virtual display.
                // The host activity only exits through the assistant bubble.
                controller.handleBack()
            }
        })

        setContent {
            SakuTheme {
                ConsoleScreen(controller)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        controller.onHostResumed()
    }

    override fun onStop() {
        // The deck is no longer visible: release its virtual display and Shizuku service.
        controller.shutdown()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        if (isFinishing) controller.shutdown()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
