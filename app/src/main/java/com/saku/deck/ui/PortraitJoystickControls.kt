package com.saku.deck.ui

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.saku.deck.DeckController

/** Compact portrait controller inspired by the translucent gamepad in the reference image. */
@Composable
fun PortraitJoystickControls(c: DeckController, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val padHeight = maxHeight.coerceAtMost(176.dp)
        val stickSize = (padHeight * 0.43f).coerceIn(54.dp, 76.dp)
        val dpadSize = (padHeight * 0.34f).coerceIn(48.dp, 62.dp)
        val faceSize = (padHeight * 0.40f).coerceIn(54.dp, 70.dp)
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val body = Path().apply {
                moveTo(w * .08f, h * .46f)
                quadraticTo(w * .10f, h * .14f, w * .28f, h * .22f)
                quadraticTo(w * .50f, h * .34f, w * .72f, h * .22f)
                quadraticTo(w * .90f, h * .14f, w * .92f, h * .46f)
                lineTo(w * .84f, h * .88f)
                quadraticTo(w * .80f, h * .99f, w * .70f, h * .87f)
                lineTo(w * .58f, h * .67f)
                quadraticTo(w * .50f, h * .60f, w * .42f, h * .67f)
                lineTo(w * .30f, h * .87f)
                quadraticTo(w * .20f, h * .99f, w * .16f, h * .88f)
                close()
            }
            drawPath(body, Color(0xFF343A43).copy(alpha = .88f))
            drawPath(body, Color.White.copy(alpha = .22f), style = Stroke(width = 1.4.dp.toPx()))
            drawLine(Color.White.copy(alpha = .13f), Offset(w * .5f, h * .27f), Offset(w * .5f, h * .75f), 1.dp.toPx())
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.size(dpadSize), contentAlignment = Alignment.Center) {
                DPad(dpadSize) { code, down -> c.sendKey(code, down) }
            }
            AnalogStick(
                diameter = stickSize,
                onMove = { x, y, active -> c.sendStick(false, x, y, active) },
            )
            AnalogStick(
                diameter = stickSize,
                onMove = { x, y, active -> c.sendStick(true, x, y, active) },
            )
            Box(Modifier.size(faceSize), contentAlignment = Alignment.Center) {
                FaceButtons(faceSize) { code, down ->
                    c.sendSkillWheel(code, 0f, 0f, down)
                }
            }
        }
    }
}
