package com.saku.deck.ui

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
            .border(2.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
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

/** Tap to activate a mapped action, or hold-drag-release to aim a Mobile Legends skill wheel. */
@Composable
fun SkillWheelButton(
    label: String,
    color: Color,
    diameter: Dp,
    modifier: Modifier = Modifier,
    layoutEditing: Boolean,
    onWheel: (Float, Float, Boolean) -> Unit,
    onPositionDrag: (Float, Float) -> Unit,
) {
    var active by remember { mutableStateOf(false) }
    val wheel by rememberUpdatedState(onWheel)
    val move by rememberUpdatedState(onPositionDrag)
    val haptic = rememberHaptic()
    val gesture = Modifier.pointerInput(layoutEditing) {
        if (layoutEditing) {
            detectDragGestures { change, amount ->
                change.consume()
                move(amount.x / size.width.coerceAtLeast(1), amount.y / size.height.coerceAtLeast(1))
            }
        } else {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                active = true
                haptic()
                wheel(0f, 0f, true)
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dx = (change.position.x - down.position.x) / size.width.coerceAtLeast(1)
                        val dy = (change.position.y - down.position.y) / size.height.coerceAtLeast(1)
                        wheel(dx, dy, true)
                        change.consume()
                    }
                } finally {
                    active = false
                    wheel(0f, 0f, false)
                }
            }
        }
    }
    Box(
        modifier
            .size(diameter)
            .border(2.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
            .clip(CircleShape)
            .background(if (active) color.pressedTone() else color)
            .then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (diameter.value * 0.4f).sp)
        Text(
            if (layoutEditing) "DRAG" else "↗",
            Modifier.align(Alignment.BottomEnd),
            color = Color.White.copy(alpha = 0.82f),
            fontSize = if (layoutEditing) 6.sp else 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Compact action button with a drag mode for the control-layout editor. */
@Composable
fun LayoutRoundButton(
    label: String,
    color: Color,
    diameter: Dp,
    modifier: Modifier = Modifier,
    layoutEditing: Boolean,
    onPress: (Boolean) -> Unit,
    onPositionDrag: (Float, Float) -> Unit,
) {
    val (pressed, press) = rememberPress(onPress)
    val move by rememberUpdatedState(onPositionDrag)
    val drag = Modifier.pointerInput(layoutEditing) {
        if (layoutEditing) {
            detectDragGestures { change, amount ->
                change.consume()
                move(amount.x / size.width.coerceAtLeast(1), amount.y / size.height.coerceAtLeast(1))
            }
        }
    }
    Box(
        modifier.size(diameter)
            .border(2.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
            .clip(CircleShape)
            .background(if (pressed) color.pressedTone() else color)
            .then(if (layoutEditing) drag else press),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (diameter.value * 0.30f).sp)
        if (layoutEditing) Text("DRAG", Modifier.align(Alignment.BottomCenter), color = Color.White.copy(alpha = 0.82f), fontSize = 6.sp)
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
                .border(1.dp, Color.Black.copy(alpha = 0.30f), RoundedCornerShape(50))
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
fun DPad(
    diameter: Dp,
    hiddenDirections: Set<Int> = emptySet(),
    onKey: (Int, Boolean) -> Unit,
) {
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
                        val next = dpadDirections(pos, area) - hiddenDirections
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
            // Center hub remains visible; each directional arm can be hidden independently.
            drawRoundRect(Deck.Control, Offset(half - arm / 2, half - arm / 2), Size(arm, arm), radius)
            val dark = Deck.ControlDark
            val armLen = half - arm / 2
            val up = KeyEvent.KEYCODE_DPAD_UP
            val down = KeyEvent.KEYCODE_DPAD_DOWN
            val left = KeyEvent.KEYCODE_DPAD_LEFT
            val right = KeyEvent.KEYCODE_DPAD_RIGHT
            if (up !in hiddenDirections) {
                drawRoundRect(if (up in active) dark else Deck.Control, Offset(half - arm / 2, 0f), Size(arm, half + arm / 2), radius)
            }
            if (down !in hiddenDirections) {
                drawRoundRect(if (down in active) dark else Deck.Control, Offset(half - arm / 2, half - arm / 2), Size(arm, half + arm / 2), radius)
            }
            if (left !in hiddenDirections) {
                drawRoundRect(if (left in active) dark else Deck.Control, Offset(0f, half - arm / 2), Size(half + arm / 2, arm), radius)
            }
            if (right !in hiddenDirections) {
                drawRoundRect(if (right in active) dark else Deck.Control, Offset(half - arm / 2, half - arm / 2), Size(half + arm / 2, arm), radius)
            }

            val mark = Deck.BodyShade.copy(alpha = 0.75f)
            val t = arm * 0.16f
            fun tri(a: Offset, b: Offset, c: Offset) {
                drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }, mark)
            }
            val edge = arm * 0.42f
            if (up !in hiddenDirections) tri(Offset(half, edge - t), Offset(half - t, edge + t * 0.4f), Offset(half + t, edge + t * 0.4f))
            if (down !in hiddenDirections) tri(Offset(half, len - edge + t), Offset(half - t, len - edge - t * 0.4f), Offset(half + t, len - edge - t * 0.4f))
            if (left !in hiddenDirections) tri(Offset(edge - t, half), Offset(edge + t * 0.4f, half - t), Offset(edge + t * 0.4f, half + t))
            if (right !in hiddenDirections) tri(Offset(len - edge + t, half), Offset(len - edge - t * 0.4f, half - t), Offset(len - edge - t * 0.4f, half + t))

            drawCircle(Color.Black.copy(alpha = 0.12f), arm * 0.26f, Offset(half, half))
        }
    }
}

/** Analog stick returning x/y in -1..1 (right/down positive). */
@Composable
fun AnalogStick(
    diameter: Dp,
    onMove: (Float, Float, Boolean) -> Unit,
    layoutEditing: Boolean = false,
    onPositionDrag: (Float, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val callback by rememberUpdatedState(onMove)
    val moveLayout by rememberUpdatedState(onPositionDrag)

    Box(
        modifier
            .size(diameter)
            .pointerInput(layoutEditing) {
                if (layoutEditing) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        moveLayout(amount.x / size.width.coerceAtLeast(1), amount.y / size.height.coerceAtLeast(1))
                    }
                } else {
                    awaitEachGesture {
                        val area = size
                        val center = Offset(area.width / 2f, area.height / 2f)
                        val reach = area.width / 2f * 0.7f
                        fun set(pos: Offset) {
                            var v = (pos - center) / reach
                            val len = sqrt(v.x * v.x + v.y * v.y)
                            if (len > 1f) v /= len
                            // Quantize the drawn knob: raw pointer events would
                            // otherwise redraw this canvas up to 120 times per
                            // second while the stick is held, costing GPU time.
                            knob = Offset(
                                (v.x * 12f).toInt() / 12f,
                                (v.y * 12f).toInt() / 12f,
                            )
                            callback(v.x, v.y, true)
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
                            callback(0f, 0f, false)
                        }
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
        if (layoutEditing) {
            Text("DRAG", Modifier.align(Alignment.BottomCenter), color = Deck.Ink, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        }
    }
}
