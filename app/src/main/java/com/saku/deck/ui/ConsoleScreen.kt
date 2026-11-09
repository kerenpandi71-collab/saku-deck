package com.saku.deck.ui

import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.saku.deck.DeckController
import com.saku.deck.ShizukuState

@Composable
fun ConsoleScreen(c: DeckController) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Deck.BodyHighlight, Deck.Body, Deck.BodyShade)))
            .windowInsetsPadding(WindowInsets.displayCutout)
    ) {
        val side = (maxWidth * 0.22f).coerceIn(150.dp, 260.dp)
        val scale = minOf(maxHeight.value / 360f, side.value / 180f).coerceIn(0.7f, 1.8f)

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SidePanel(left = true, c = c, scale = scale, modifier = Modifier.width(side).fillMaxHeight())
            ScreenArea(c, Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp))
            SidePanel(left = false, c = c, scale = scale, modifier = Modifier.width(side).fillMaxHeight())
        }
        if (c.mappingMenuOpen) MappingDialog(c)
    }
}

@Composable
private fun SidePanel(left: Boolean, c: DeckController, scale: Float, modifier: Modifier) {
    Column(
        modifier.padding(vertical = (4 * scale).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)) {
            if (left) {
                ShoulderButton("L2", scale) { c.setTrigger(true, it) }
                ShoulderButton("L1", scale) { c.sendKey(KeyEvent.KEYCODE_BUTTON_L1, it) }
            } else {
                ShoulderButton("R1", scale) { c.sendKey(KeyEvent.KEYCODE_BUTTON_R1, it) }
                ShoulderButton("R2", scale) { c.setTrigger(false, it) }
            }
        }

        if (left) {
            DPad((112 * scale).dp) { code, down -> c.sendKey(code, down) }
        } else {
            FaceButtons((118 * scale).dp) { code, down -> c.sendKey(code, down) }
        }

        AnalogStick((92 * scale).dp) { x, y, active -> c.sendStick(!left, x, y, active) }

        Row(
            horizontalArrangement = Arrangement.spacedBy((10 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (left) {
                PillButton("SELECT", scale) { c.sendKey(KeyEvent.KEYCODE_BUTTON_SELECT, it) }
                PillButton("KEMBALI", scale) { c.sendKey(KeyEvent.KEYCODE_BACK, it) }
                PillButton("HOME", scale) { c.sendKey(KeyEvent.KEYCODE_HOME, it) }
            } else {
                PillButton("START", scale) { c.sendKey(KeyEvent.KEYCODE_BUTTON_START, it) }
                PillButton("MENU", scale, accent = true) { if (it) c.openPicker() }
            }
        }
    }
}

@Composable
private fun ScreenArea(c: DeckController, modifier: Modifier) {
    val bezelShape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .shadow(8.dp, bezelShape)
            .clip(bezelShape)
            .background(Deck.Bezel)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(Deck.ScreenOff)
        ) {
            VirtualSurface(c, Modifier.fillMaxSize())
            if (c.mappingTargetId != null) MappingMarkers(c)
            when {
                c.shizukuState != ShizukuState.READY -> ShizukuPanel(c)
                c.displayId < 0 -> LoadingPanel(c)
                c.pickerOpen -> AppPicker(c)
            }
            c.message?.let { text ->
                MessageBanner(text, Modifier.align(Alignment.BottomCenter)) { c.message = null }
            }
            if (c.currentApp != null && c.displayId >= 0) {
                val mappingActive = c.mappingTargetId != null
                Text(
                    if (mappingActive) "BATAL MAP" else "MAP",
                    Modifier.align(Alignment.TopEnd)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (mappingActive) Color.Black.copy(alpha = 0.82f) else Deck.Accent.copy(alpha = 0.92f))
                        .clickable { if (mappingActive) c.cancelMapping() else c.openMappingMenu() }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    color = Color.White,
                    fontSize = 10.sp,
                )
            }
        }
        Text(
            "SAKU DECK",
            Modifier.align(Alignment.BottomCenter).offset(y = 13.dp),
            fontSize = 7.sp,
            letterSpacing = 3.sp,
            color = Color.White.copy(alpha = 0.35f),
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = (-7).dp, y = (-4).dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(if (c.displayId >= 0) Deck.Accent else Deck.Control)
        )
    }
}

@Composable
private fun MappingMarkers(c: DeckController) {
    val selected = c.touchMappings.firstOrNull { it.id == c.mappingTargetId }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val diameter = 34.dp
        c.touchMappings.forEach { target ->
            Box(
                Modifier
                    .offset(x = maxWidth * target.x - diameter / 2, y = maxHeight * target.y - diameter / 2)
                    .size(diameter)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (target.id == selected?.id) Deck.Accent else Deck.Control.copy(alpha = 0.94f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(target.badge, color = Color.White, fontSize = 8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
        selected?.let { target ->
            Text(
                "Ketuk lokasi ${target.label}",
                Modifier.align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = Color.White,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun VirtualSurface(c: DeckController, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {}

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        c.onSurfaceReady(holder.surface, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        c.onSurfaceGone()
                    }
                })
                setOnTouchListener { v, e -> c.forwardTouch(e, v.width, v.height) }
            }
        },
    )
}
