package com.saku.deck.ui

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

@Composable
private fun rememberHaptic(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } }
}

/** Reports press (true) on first finger down and release (false) when every finger lifts. */
private fun Modifier.pressable(onPress: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onPress(true)
        try {
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        } finally {
            onPress(false)
        }
    }
}

@Composable
private fun rememberPress(onPress: (Boolean) -> Unit): Pair<Boolean, Modifier> {
    var pressed by remember { mutableStateOf(false) }
    val haptic = rememberHaptic()
    val callback by rememberUpdatedState(onPress)
    val modifier = remember {
        Modifier.pressable { p ->
            pressed = p
            if (p) haptic()
            callback(p)
        }
    }
    return pressed to modifier
}

private fun Color.pressedTone() = lerp(this, Color.Black, 0.22f)

@Composable
fun RoundButton(label: String, color: Color, diameter: Dp, onPress: (Boolean) -> Unit) {
    val (pressed, press) = rememberPress(onPress)
    Box(
        Modifier
            .size(diameter)
            .shadow(if (pressed) 1.dp else 4.dp, CircleShape)
            .clip(CircleShape)
            .background(if (pressed) color.pressedTone() else color)
            .then(press),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (diameter.value * 0.4f).sp,
        )
    }
}

/** X top, Y left, A right, B bottom. */
@Composable
fun FaceButtons(diameter: Dp, onKey: (Int, Boolean) -> Unit) {
    val button = diameter * 0.36f
    Box(Modifier.size(diameter)) {
        Box(Modifier.align(Alignment.TopCenter)) {
            RoundButton("X", Deck.ButtonX, button) { onKey(KeyEvent.KEYCODE_BUTTON_X, it) }
        }
        Box(Modifier.align(Alignment.CenterStart)) {
            RoundButton("Y", Deck.ButtonY, button) { onKey(KeyEvent.KEYCODE_BUTTON_Y, it) }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            RoundButton("A", Deck.ButtonA, button) { onKey(KeyEvent.KEYCODE_BUTTON_A, it) }
        }
        Box(Modifier.align(Alignment.BottomCenter)) {
            RoundButton("B", Deck.ButtonB, button) { onKey(KeyEvent.KEYCODE_BUTTON_B, it) }
        }
    }
}

@Composable
fun ShoulderButton(label: String, scale: Float, onPress: (Boolean) -> Unit) {
    val (pressed, press) = rememberPress(onPress)
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(width = (58 * scale).dp, height = (24 * scale).dp)
            .clip(shape)
            .background(if (pressed) Deck.Control else Deck.BodyShade)
            .border(1.dp, Deck.Control.copy(alpha = 0.35f), shape)
            .then(press),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed) Color.White else Deck.Ink,
            fontWeight = FontWeight.SemiBold,
            fontSize = (11 * scale).sp,
        )
    }
}

@Composable
fun PillButton(label: String, scale: Float, accent: Boolean = false, onPress: (Boolean) -> Unit) {
    val (pressed, press) = rememberPress(onPress)
    val color = if (accent) Deck.Accent else Deck.Control
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((3 * scale).dp),
    ) {
        Box(
            Modifier
                .size(width = (36 * scale).dp, height = (14 * scale).dp)
                .shadow(if (pressed) 0.dp else 2.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(if (pressed) color.pressedTone() else color)
                .then(press)
        )
        Text(
            label,
            color = Deck.Ink.copy(alpha = 0.65f),
            fontSize = (8 * scale).sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (0.8 * scale).sp,
        )
    }
}

private fun dpadDirections(p: Offset, area: IntSize): Set<Int> {
    val cx = area.width / 2f
    val cy = area.height / 2f
    val dx = (p.x - cx) / cx
    val dy = (p.y - cy) / cy
    if (abs(dx) < 0.22f && abs(dy) < 0.22f) return emptySet()
    val angle = Math.toDegrees(atan2(dy, dx).toDouble())
    val set = mutableSetOf<Int>()
    if (angle > -67.5 && angle < 67.5) set += KeyEvent.KEYCODE_DPAD_RIGHT
    if (angle > 22.5 && angle < 157.5) set += KeyEvent.KEYCODE_DPAD_DOWN
    if (angle > 112.5 || angle < -112.5) set += KeyEvent.KEYCODE_DPAD_LEFT
    if (angle > -157.5 && angle < -22.5) set += KeyEvent.KEYCODE_DPAD_UP
    return set
}

/** Slide-able D-pad with diagonals. */
@Composable
fun DPad(diameter: Dp, onKey: (Int, Boolean) -> Unit) {
    var active by remember { mutableStateOf(emptySet<Int>()) }
    val haptic = rememberHaptic()
    val callback by rememberUpdatedState(onKey)

    Box(
        Modifier
            .size(diameter)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val area = size
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var current = emptySet<Int>()
                    fun update(pos: Offset) {
                        val next = dpadDirections(pos, area)
                        if (next == current) return
                        (current - next).forEach { callback(it, false) }
                        val added = next - current
                        added.forEach { callback(it, true) }
                        if (added.isNotEmpty()) haptic()
                        current = next
                        active = next
                    }
                    try {
                        update(down.position)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            update(change.position)
                            change.consume()
                        }
                    } finally {
                        current.forEach { callback(it, false) }
                        active = emptySet()
                    }
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val len = size.minDimension
            val arm = len * 0.34f
            val half = len / 2f
            val radius = CornerRadius(arm * 0.22f)
            val shadow = Color.Black.copy(alpha = 0.16f)
            val lift = len * 0.025f

            // Soft drop shadow
            drawRoundRect(shadow, Offset(half - arm / 2, lift), Size(arm, len), radius)
            drawRoundRect(shadow, Offset(0f, half - arm / 2 + lift), Size(len, arm), radius)
            // Cross
            drawRoundRect(Deck.Control, Offset(half - arm / 2, 0f), Size(arm, len), radius)
            drawRoundRect(Deck.Control, Offset(0f, half - arm / 2), Size(len, arm), radius)

            val dark = Deck.ControlDark
            val armLen = half - arm / 2
            if (KeyEvent.KEYCODE_DPAD_UP in active)
                drawRoundRect(dark, Offset(half - arm / 2, 0f), Size(arm, armLen + arm * 0.2f), radius)
            if (KeyEvent.KEYCODE_DPAD_DOWN in active)
                drawRoundRect(dark, Offset(half - arm / 2, half + arm * 0.3f), Size(arm, armLen + arm * 0.2f), radius)
            if (KeyEvent.KEYCODE_DPAD_LEFT in active)
                drawRoundRect(dark, Offset(0f, half - arm / 2), Size(armLen + arm * 0.2f, arm), radius)
            if (KeyEvent.KEYCODE_DPAD_RIGHT in active)
                drawRoundRect(dark, Offset(half + arm * 0.3f, half - arm / 2), Size(armLen + arm * 0.2f, arm), radius)

            // Arrow marks
            val mark = Deck.BodyShade.copy(alpha = 0.75f)
            val t = arm * 0.16f
            fun tri(a: Offset, b: Offset, c: Offset) {
                drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }, mark)
            }
            val edge = arm * 0.42f
            tri(Offset(half, edge - t), Offset(half - t, edge + t * 0.4f), Offset(half + t, edge + t * 0.4f))
            tri(Offset(half, len - edge + t), Offset(half - t, len - edge - t * 0.4f), Offset(half + t, len - edge - t * 0.4f))
            tri(Offset(edge - t, half), Offset(edge + t * 0.4f, half - t), Offset(edge + t * 0.4f, half + t))
            tri(Offset(len - edge + t, half), Offset(len - edge - t * 0.4f, half - t), Offset(len - edge - t * 0.4f, half + t))

            drawCircle(Color.Black.copy(alpha = 0.12f), arm * 0.26f, Offset(half, half))
        }
    }
}

/** Analog stick returning x/y in -1..1 (right/down positive). */
@Composable
fun AnalogStick(diameter: Dp, onMove: (Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val callback by rememberUpdatedState(onMove)

    Box(
        Modifier
            .size(diameter)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val area = size
                    val center = Offset(area.width / 2f, area.height / 2f)
                    val reach = area.width / 2f * 0.7f
                    fun set(pos: Offset) {
                        var v = (pos - center) / reach
                        val len = sqrt(v.x * v.x + v.y * v.y)
                        if (len > 1f) v /= len
                        knob = v
                        callback(v.x, v.y)
                    }
                    val down = awaitFirstDown(requireUnconsumed = false)
                    try {
                        set(down.position)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            set(change.position)
                            change.consume()
                        }
                    } finally {
                        knob = Offset.Zero
                        callback(0f, 0f)
                    }
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Deck.BodyShade, r, c)
            drawCircle(Color.Black.copy(alpha = 0.10f), r * 0.97f, c, style = Stroke(r * 0.06f))
            val travel = r * 0.36f
            val k = c + Offset(knob.x * travel, knob.y * travel)
            drawCircle(Color.Black.copy(alpha = 0.20f), r * 0.62f, k + Offset(0f, r * 0.07f))
            drawCircle(Deck.Control, r * 0.62f, k)
            drawCircle(Deck.ControlDark, r * 0.44f, k, style = Stroke(r * 0.05f))
            drawCircle(Color.White.copy(alpha = 0.10f), r * 0.30f, k - Offset(r * 0.12f, r * 0.14f))
        }
    }
}
