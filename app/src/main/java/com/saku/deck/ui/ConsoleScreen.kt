package com.saku.deck.ui

import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.saku.deck.DeckController
import com.saku.deck.ShizukuState

@Composable
fun ConsoleScreen(c: DeckController) {
    val hostActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    LaunchedEffect(c) {
        while (true) {
            delay(1000)
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Deck.BodyHighlight, Deck.Body, Deck.BodyShade)))
            .windowInsetsPadding(WindowInsets.displayCutout)
            // IMPORTANT: keep imePadding OFF this root. Padding the whole
            // deck resized the game SurfaceView on every IME animation
            // frame, and every resizeVirtualDisplay made the system
            // re-evaluate window focus between the host and the virtual
            // display, so the soft keyboard flickered open/closed in a loop.
            // Only the keyboard bridge pads itself above the IME, so the
            // virtual display keeps a stable size while typing.
    ) {
        val side = (maxWidth * 0.22f).coerceIn(150.dp, 260.dp)
        val scale = minOf(maxHeight.value / 360f, side.value / 180f).coerceIn(0.7f, 1.8f)

        if (c.titanPortraitMode) {
            TitanPortraitShell(c, Modifier.fillMaxSize())
        } else if (!c.gamepadHidden) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SidePanel(left = true, c = c, scale = scale, modifier = Modifier.width(side).fillMaxHeight())
                ScreenArea(c, Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp))
                SidePanel(left = false, c = c, scale = scale, modifier = Modifier.width(side).fillMaxHeight())
            }
        } else {
            ScreenArea(c, Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp))
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (c.assistantMenuOpen) {
                listOf(
                    (if (c.titanPortraitMode) "MODE LANDSCAPE" else "MODE PORTRAIT TITAN") to { c.toggleTitanPortraitMode() },
                    (if (c.performanceMode) "MODE KUALITAS PENUH" else "MODE PERFORMA") to { c.togglePerformanceMode() },
                    (if (c.gamepadHidden) "TAMPILKAN PAD" else "SEMBUNYIKAN PAD") to { c.toggleGamepadHidden() },
                    (if (c.externalKeyboardOpen) "TUTUP KEYBOARD LUAR" else "KEYBOARD LUAR") to {
                        c.updateExternalKeyboardOpen(!c.externalKeyboardOpen)
                    },
                    "MAPPING ML" to { c.openMappingMenu() },
                    "VISIBILITAS" to { c.visibilityMenuOpen = true },
                    "UKURAN A/B/X/Y" to { c.controlSizeMenuOpen = true },
                    "ATUR POSISI" to { c.beginControlLayoutEdit() },
                    "KELUAR DARI SAKU DECK" to { hostActivity?.finish() },
                ).forEach { (label, action) ->
                    Text(label, Modifier.clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.88f))
                        .clickable { action(); c.assistantMenuOpen = false }
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                        color = Color.White, fontSize = 10.sp)
                }
            }
            if ("assistant_bubble" in c.hiddenControls) {
                Text(
                    "TAMPILKAN ASISTEN",
                    Modifier.clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.88f))
                        .clickable { c.setControlHidden("assistant_bubble", false) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = Color.White,
                    fontSize = 9.sp,
                )
            } else {
                Box(
                    Modifier.size(48.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                        .background(Deck.Accent)
                        .clickable { c.assistantMenuOpen = !c.assistantMenuOpen },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (c.assistantMenuOpen) "×" else "●", color = Color.White, fontSize = 22.sp)
                }
            }
        }
        if (c.externalKeyboardOpen) {
            ExternalKeyboardBridge(
                c,
                // The bridge alone lifts above the soft keyboard; the deck
                // itself (and the game surface) keeps its exact size.
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        if (c.visibilityMenuOpen) ControlVisibilityDialog(c)
        if (c.controlSizeMenuOpen) ControlSizeDialog(c)
        if (c.controlLayoutEditing) ControlLayoutBar(c, Modifier.align(Alignment.TopCenter))
        if (c.mappingMenuOpen) MappingDialog(c)
    }
}

@Composable
private fun TitanPortraitShell(c: DeckController, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Use the full physical phone width; the old 360dp cap made the Titan body look tiny
        // on the Redmi 12C when rendered inside the portrait host window.
        val deviceWidth = (maxWidth - 12.dp).coerceAtLeast(1.dp)
        Column(
            Modifier.width(deviceWidth)
                .height((maxHeight - 12.dp).coerceAtLeast(1.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF17191C))
                .padding(horizontal = 9.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TitanStatusStrip(c)
            // Keep the virtual screen tall below the compact status strip.
            ScreenArea(c, Modifier.fillMaxWidth().weight(1.55f))
            TitanNavigationRow(c)
            TitanKeyboard(c)
            TitanTrackpad(c, Modifier.fillMaxWidth().weight(0.48f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("UNIHERTZ-STYLE", color = Color(0xFF92979E), fontSize = 7.sp, letterSpacing = 1.2.sp)
                Text("SAKU DECK", color = Color(0xFFB7BBC1), fontSize = 7.sp, letterSpacing = 1.2.sp)
            }
        }
    }
}

@Composable
private fun TitanTrackpad(c: DeckController, modifier: Modifier) {
    AndroidView(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .pointerInput(c) {
                // One finger: move cursor. Short tap: click. Hold still for
                // 180ms, then move: drag. Two fingers: horizontal/vertical scroll.
                // AwaitPointerEventScope only uses its supported pointer suspend
                // APIs; long-press timing is evaluated from MotionEvent timestamps.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val downTime = down.uptimeMillis
                    var hold = false
                    var moved = false
                    var travel = 0f
                    var releasedAt = 0L
                    try {
                        var active = true
                        while (active) {
                            val event = awaitPointerEvent()
                            val primary = event.changes.firstOrNull { it.id == down.id }
                            if (primary == null) {
                                active = false
                                continue
                            }
                            if (!primary.pressed) {
                                releasedAt = primary.uptimeMillis
                                active = false
                                continue
                            }

                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                if (hold) {
                                    c.trackpadPress(false)
                                    hold = false
                                }
                                val dx = pressed.sumOf { it.positionChange().x.toDouble() }.toFloat()
                                val dy = pressed.sumOf { it.positionChange().y.toDouble() }.toFloat()
                                if (dx != 0f || dy != 0f) {
                                    c.scrollTrackpad(
                                        -dx / pressed.size / 28f,
                                        -dy / pressed.size / 28f,
                                    )
                                }
                                moved = true
                            } else {
                                val delta = primary.positionChange()
                                val elapsed = primary.uptimeMillis - downTime

                                // Start a press before applying this movement when
                                // the user has held for the threshold. This turns
                                // hold-then-slide into a continuous drag stream.
                                if (!hold && !moved && elapsed >= 180L && travel <= 14f) {
                                    hold = true
                                    c.trackpadPress(true)
                                }

                                if (delta.x != 0f || delta.y != 0f) {
                                    c.moveTrackpadCursor(delta.x, delta.y, size.width, size.height)
                                    travel += kotlin.math.abs(delta.x) + kotlin.math.abs(delta.y)
                                    if (!hold && travel > 14f) moved = true
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } finally {
                        if (hold) c.trackpadPress(false)
                    }

                    // A short, stationary contact is a click. A long hold
                    // without movement is not mistaken for a click.
                    if (!hold && !moved && releasedAt > 0L && releasedAt - downTime < 250L) {
                        c.tapTrackpadCursor()
                    }
                }
            },
        factory = { context ->
            View(context).apply {
                setBackgroundColor(android.graphics.Color.rgb(48, 53, 59))
                isFocusable = false
                isFocusableInTouchMode = false
            }
        },
    )
}

@Composable
private fun TitanStatusStrip(c: DeckController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            val intent = context.registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            battery = if (level >= 0 && scale > 0) level * 100 / scale else null
            c.refreshVirtualFps()
            delay(1000)
        }
    }
    val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))
    val date = java.text.SimpleDateFormat("EEE, dd MMM", java.util.Locale.getDefault()).format(java.util.Date(now))
    Row(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF25292E)).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(clock, color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(date, color = Color(0xFFB9C0C8), fontSize = 9.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(battery?.let { "BAT $it%" } ?: "BAT --", color = Color(0xFFDDE2E7), fontSize = 9.sp)
            Text(c.virtualFps.takeIf { it > 0 }?.let { "$it FPS" } ?: "-- FPS", color = Color(0xFFB9D8FF), fontSize = 9.sp)
        }
    }
}

@Composable
private fun TitanNavigationRow(c: DeckController) {
    // Keep only the two navigation controls requested: Back and Home.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            "‹" to KeyEvent.KEYCODE_BACK,
            "⌂" to KeyEvent.KEYCODE_HOME,
        ).forEach { (label, code) ->
            Box(
                Modifier.weight(1f).height(34.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF34383D))
                    .clickable { c.sendKey(code, true); c.sendKey(code, false) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = Color(0xFFE1E3E6), fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun TitanKeyboard(c: DeckController) {
    val repeatScope = rememberCoroutineScope()
    val letterRows = listOf(
        listOf("Q","W","E","R","T","Y","U","I","O","P"),
        listOf("A","S","D","F","G","H","J","K","L"),
        listOf("⇧","Z","X","C","V","B","N","M","⌫"),
    )
    val numberRows = listOf(
        listOf("1","2","3","4","5","6","7","8","9","0"),
        listOf("@","#","$","%","&","-","+","(",")","/"),
        listOf("⇧","=","*","\"","'",";",":","!","⌫"),
    )
    var symbols by remember { mutableStateOf(false) }
    var shifted by remember { mutableStateOf(false) }
    val rows = if (symbols) numberRows else letterRows
    val shiftedSymbols = mapOf(
        "1" to "!", "2" to "@", "3" to "#", "4" to "$", "5" to "%",
        "6" to "^", "7" to "&", "8" to "*", "9" to "(", "0" to ")",
        "=" to "+", "*" to "×", "\"" to "\"", "'" to "`",
        ";" to ":", ":" to ";", "!" to "?", "@" to "~",
    )

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF24272B)).padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            TitanUtilityKey("COPY", Modifier.weight(1f)) {
                c.sendShortcut(KeyEvent.KEYCODE_C)
            }
            TitanUtilityKey("PASTE", Modifier.weight(1f)) {
                c.sendShortcut(KeyEvent.KEYCODE_V)
            }
            TitanUtilityKey("SELECT ALL", Modifier.weight(1.35f)) {
                c.sendShortcut(KeyEvent.KEYCODE_A)
            }
        }
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                row.forEach { key ->
                    val wide = key == "⇧" || key == "⌫"
                    Box(
                        Modifier.weight(if (wide) 1.35f else 1f).height(34.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF454A50))
                            .pointerInput(key, symbols, shifted) {
                                if (key == "⌫") {
                                    detectTapGestures(onPress = {
                                        val startedAt = android.os.SystemClock.uptimeMillis()
                                        val deleteJob = repeatScope.launch {
                                            delay(420)
                                            while (true) {
                                                c.sendBackspace(1)
                                                delay(65)
                                            }
                                        }
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            deleteJob.cancel()
                                            if (android.os.SystemClock.uptimeMillis() - startedAt < 420L) {
                                                c.sendBackspace(1)
                                            }
                                        }
                                    })
                                } else {
                                    detectTapGestures(onTap = {
                                        when (key) {
                                            "⇧" -> shifted = !shifted
                                            else -> {
                                                val output = when {
                                                    symbols && shifted -> shiftedSymbols[key] ?: key
                                                    symbols -> key
                                                    shifted -> key
                                                    else -> key.lowercase()
                                                }
                                                c.sendText(output)
                                                if (shifted) shifted = false
                                            }
                                        }
                                    })
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            key,
                            color = Color(0xFFF0F1F2),
                            fontSize = 12.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            TitanUtilityKey(if (symbols) "ABC" else "123", Modifier.weight(1f)) {
                symbols = !symbols
                shifted = false
            }
            TitanUtilityKey(",", Modifier.weight(0.65f)) { c.sendText(",") }
            TitanUtilityKey("SPACE", Modifier.weight(3.4f)) { c.sendText(" ") }
            TitanUtilityKey(".", Modifier.weight(0.65f)) { c.sendText(".") }
            TitanUtilityKey("↵", Modifier.weight(1f)) {
                c.sendKey(KeyEvent.KEYCODE_ENTER, true)
                c.sendKey(KeyEvent.KEYCODE_ENTER, false)
            }
        }
    }
}

@Composable
private fun TitanUtilityKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(31.dp).clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF454A50)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color(0xFFE7E9EB), fontSize = 9.sp)
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
                if ("l2" !in c.hiddenControls) ShoulderButton("L2", scale) { c.setTrigger(true, it) }
                if ("l1" !in c.hiddenControls) ShoulderButton("L1", scale) { c.sendKey(KeyEvent.KEYCODE_BUTTON_L1, it) }
            } else {
                if ("r1" !in c.hiddenControls) ShoulderButton("R1", scale) { c.sendKey(KeyEvent.KEYCODE_BUTTON_R1, it) }
                if ("r2" !in c.hiddenControls) ShoulderButton("R2", scale) { c.setTrigger(false, it) }
            }
        }

        if (left) {
            if ("dpad" !in c.hiddenControls) DPad(
                diameter = (112 * scale).dp,
                hiddenDirections = buildSet {
                    if ("dpad_up" in c.hiddenControls) add(KeyEvent.KEYCODE_DPAD_UP)
                    if ("dpad_down" in c.hiddenControls) add(KeyEvent.KEYCODE_DPAD_DOWN)
                    if ("dpad_left" in c.hiddenControls) add(KeyEvent.KEYCODE_DPAD_LEFT)
                    if ("dpad_right" in c.hiddenControls) add(KeyEvent.KEYCODE_DPAD_RIGHT)
                },
                onKey = { code, down -> c.sendKey(code, down) },
            )
            if ("left_stick" !in c.hiddenControls) AnalogStick(
                diameter = (92 * scale).dp,
                onMove = { x, y, active -> c.sendStick(false, x, y, active) },
            )
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val faceControls = listOf(
                    Triple("face_x", KeyEvent.KEYCODE_BUTTON_X, Deck.ButtonX) to c.controlSizes["face_x"]!!.dp,
                    Triple("face_y", KeyEvent.KEYCODE_BUTTON_Y, Deck.ButtonY) to c.controlSizes["face_y"]!!.dp,
                    Triple("face_a", KeyEvent.KEYCODE_BUTTON_A, Deck.ButtonA) to c.controlSizes["face_a"]!!.dp,
                    Triple("face_b", KeyEvent.KEYCODE_BUTTON_B, Deck.ButtonB) to c.controlSizes["face_b"]!!.dp,
                ).filterNot { (control, _) -> control.first in c.hiddenControls }
                faceControls.forEach { (control, buttonSize) ->
                    val (id, keyCode, color) = control
                    val position = c.controlPositions.first { it.id == id }
                    SkillWheelButton(
                        label = id.substringAfter("face_").uppercase(),
                        color = color,
                        diameter = buttonSize,
                        modifier = Modifier.offset(
                            x = maxWidth * position.x - buttonSize / 2,
                            y = maxHeight * position.y - buttonSize / 2,
                        ),
                        layoutEditing = c.controlLayoutEditing,
                        onWheel = { dx, dy, pressed -> c.sendSkillWheel(keyCode, dx, dy, pressed) },
                        onPositionDrag = { dx, dy ->
                            c.moveControlPosition(
                                id,
                                position.x + dx * buttonSize.value / maxWidth.value.coerceAtLeast(1f),
                                position.y + dy * buttonSize.value / maxHeight.value.coerceAtLeast(1f),
                                buttonSize.value / (2 * maxWidth.value.coerceAtLeast(1f)),
                                buttonSize.value / (2 * maxHeight.value.coerceAtLeast(1f)),
                            )
                        },
                    )
                }
                val extraControls = listOf(
                    Triple("extra_spell", "SP", KeyEvent.KEYCODE_BUTTON_R2) to Deck.Accent,
                    Triple("extra_regen", "RG", KeyEvent.KEYCODE_BUTTON_L1) to Deck.ButtonY,
                    Triple("extra_recall", "RC", KeyEvent.KEYCODE_BUTTON_SELECT) to Deck.ButtonX,
                ).filterNot { (control, _) -> control.first in c.hiddenControls }
                extraControls.forEach { (control, color) ->
                    val (id, label, keyCode) = control
                    val buttonSize = 50.dp
                    val position = c.controlPositions.first { it.id == id }
                    val buttonModifier = Modifier.offset(
                        x = maxWidth * position.x - buttonSize / 2,
                        y = maxHeight * position.y - buttonSize / 2,
                    )
                    val moveButton = { dx: Float, dy: Float ->
                        c.moveControlPosition(
                            id,
                            position.x + dx * buttonSize.value / maxWidth.value.coerceAtLeast(1f),
                            position.y + dy * buttonSize.value / maxHeight.value.coerceAtLeast(1f),
                            buttonSize.value / (2 * maxWidth.value.coerceAtLeast(1f)),
                            buttonSize.value / (2 * maxHeight.value.coerceAtLeast(1f)),
                        )
                    }
                    if (id == "extra_spell") {
                        SkillWheelButton(
                            label = label,
                            color = color,
                            diameter = buttonSize,
                            modifier = buttonModifier,
                            layoutEditing = c.controlLayoutEditing,
                            // Spell follows Y's hold-drag-release skill-wheel behavior.
                            onWheel = { dx, dy, pressed -> c.sendSkillWheel(keyCode, dx, dy, pressed) },
                            onPositionDrag = moveButton,
                        )
                    } else {
                        LayoutRoundButton(
                            label = label,
                            color = color,
                            diameter = buttonSize,
                            modifier = buttonModifier,
                            layoutEditing = c.controlLayoutEditing,
                            onPress = { pressed -> c.sendKey(keyCode, pressed) },
                            onPositionDrag = moveButton,
                        )
                    }
                }
                val stickSize = (86 * scale).dp
                val stickPosition = c.controlPositions.first { it.id == "right_stick" }
                if ("right_stick" !in c.hiddenControls) AnalogStick(
                    diameter = stickSize,
                    modifier = Modifier.offset(
                        x = maxWidth * stickPosition.x - stickSize / 2,
                        y = maxHeight * stickPosition.y - stickSize / 2,
                    ),
                    onMove = { x, y, active -> c.sendStick(true, x, y, active) },
                    layoutEditing = c.controlLayoutEditing,
                    onPositionDrag = { dx, dy ->
                        c.moveControlPosition(
                            "right_stick",
                            stickPosition.x + dx * stickSize.value / maxWidth.value.coerceAtLeast(1f),
                            stickPosition.y + dy * stickSize.value / maxHeight.value.coerceAtLeast(1f),
                            stickSize.value / (2 * maxWidth.value.coerceAtLeast(1f)),
                            stickSize.value / (2 * maxHeight.value.coerceAtLeast(1f)),
                        )
                    },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy((10 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (left) {
                Column(verticalArrangement = Arrangement.spacedBy((5 * scale).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if ("select" !in c.hiddenControls) PillButton("SELECT", scale) { c.sendKey(KeyEvent.KEYCODE_BUTTON_SELECT, it) }
                    if ("start" !in c.hiddenControls) PillButton("START", scale) { c.sendKey(KeyEvent.KEYCODE_BUTTON_START, it) }
                }
                if ("back" !in c.hiddenControls) PillButton("KEMBALI", scale) { c.sendKey(KeyEvent.KEYCODE_BACK, it) }
                Column(verticalArrangement = Arrangement.spacedBy((5 * scale).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if ("home" !in c.hiddenControls) PillButton("HOME", scale) { c.sendKey(KeyEvent.KEYCODE_HOME, it) }
                    if ("menu" !in c.hiddenControls) PillButton("MENU", scale, accent = true) { if (it) c.openPicker() }
                }
            }
        }
    }
}

@Composable
private fun ScreenArea(c: DeckController, modifier: Modifier) {
    val bezelShape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(bezelShape)
            .background(Deck.Bezel)
            // Flat border instead of an elevation shadow: shadow layers force the
            // whole window to alpha-composite with the game surface every frame,
            // which costs real FPS on low-end GPUs while the pad overlay is shown.
            .border(1.dp, Color.Black.copy(alpha = 0.35f), bezelShape)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(Deck.ScreenOff)
        ) {
            VirtualSurface(c, Modifier.fillMaxSize())
            TrackpadCursor(c)
            if (!c.titanPortraitMode) {
                Text(
                    c.virtualFps.takeIf { it > 0 }?.let { "$it FPS" } ?: "-- FPS",
                    Modifier.align(Alignment.TopEnd).padding(7.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    color = Color(0xFFB9D8FF),
                    fontSize = 9.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
            if (c.mappingTargetId != null) MappingMarkers(c)
            when {
                c.shizukuState != ShizukuState.READY -> ShizukuPanel(c)
                c.displayId < 0 -> LoadingPanel(c)
                c.pickerOpen -> AppPicker(c)
            }
            c.message?.let { text ->
                MessageBanner(text, Modifier.align(Alignment.BottomCenter)) { c.message = null }
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

/**
 * Cursor sprite for the Titan trackpad, drawn over the virtual screen.
 * Android only renders its native mouse pointer for physical mouse devices,
 * never for injected events, so the host draws this pointer itself.
 */
@Composable
private fun TrackpadCursor(c: DeckController) {
    val fx = c.trackpadCursorX
    val fy = c.trackpadCursorY
    if (fx < 0f || fy < 0f) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .offset(
                    x = maxWidth * fx,
                    y = maxHeight * fy,
                )
                .size(20.dp)
        ) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(0.03f * w, 0.75f * h)
                lineTo(0.28f * w, 0.56f * h)
                lineTo(0.46f * w, 0.95f * h)
                lineTo(0.62f * w, 0.87f * h)
                lineTo(0.45f * w, 0.50f * h)
                lineTo(0.72f * w, 0.44f * h)
                close()
            }
            drawPath(path, Color.White)
            drawPath(path, Color.Black.copy(alpha = 0.55f), style = Stroke(width = 2f))
        }
    }
}


@Composable
private fun ControlLayoutBar(c: DeckController, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SERET KONTROL", color = Color.White, fontSize = 8.sp)
        Text("RESET", Modifier.clickable { c.resetControlLayout() }.padding(4.dp), color = Color.White, fontSize = 9.sp)
        Text("BATAL", Modifier.clickable { c.cancelControlLayoutEdit() }.padding(4.dp), color = Color.White, fontSize = 9.sp)
        Text("SIMPAN", Modifier.clickable { c.saveControlLayout() }.padding(4.dp), color = Deck.Accent, fontSize = 9.sp)
    }
}

@Composable
private fun ControlVisibilityDialog(c: DeckController) {
    val controls = listOf(
        "face_a" to "Tombol A",
        "face_b" to "Tombol B",
        "face_x" to "Tombol X",
        "face_y" to "Tombol Y",
        "extra_spell" to "Battle Spell",
        "extra_regen" to "Regen",
        "extra_recall" to "Recall",
        "right_stick" to "Analog kanan",
        "left_stick" to "Analog kiri",
        "dpad" to "D-Pad (semua arah)",
        "dpad_up" to "D-Pad • Atas",
        "dpad_down" to "D-Pad • Bawah",
        "dpad_left" to "D-Pad • Kiri",
        "dpad_right" to "D-Pad • Kanan",
        "l1" to "L1",
        "l2" to "L2",
        "r1" to "R1",
        "r2" to "R2",
        "select" to "Select",
        "start" to "Start",
        "home" to "Home",
        "menu" to "Menu",
        "back" to "Kembali",
        "assistant_bubble" to "Bola Asisten",
    )
    AlertDialog(
        onDismissRequest = { c.visibilityMenuOpen = false },
        title = { Text("Atur visibilitas tombol") },
        text = {
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Pilih semua kontrol yang ingin disembunyikan. Pengaturan tersimpan otomatis.",
                    fontSize = 11.sp, color = Deck.Ink.copy(alpha = 0.72f))
                controls.forEach { (id, label) ->
                    val hidden = id in c.hiddenControls
                    Row(
                        Modifier.fillMaxWidth().clickable { c.setControlHidden(id, !hidden) }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, Modifier.weight(1f), color = Deck.Ink, fontSize = 13.sp)
                        Text(if (hidden) "TERSEMBUNYI" else "DITAMPILKAN",
                            color = if (hidden) Deck.Accent else Deck.Ink.copy(alpha = 0.65f),
                            fontSize = 9.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { c.visibilityMenuOpen = false }) { Text("SELESAI") }
        },
        dismissButton = {
            TextButton(onClick = { controls.forEach { c.setControlHidden(it.first, false) } }) {
                Text("RESET")
            }
        },
    )
}

@Composable
private fun ExternalKeyboardBridge(c: DeckController, modifier: Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue()) }
    var sentText by remember { mutableStateOf("") }

    LaunchedEffect(c.externalKeyboardOpen) {
        if (c.externalKeyboardOpen) {
            // MIUI/Android can report the bridge as open before its host
            // window has regained focus from the virtual display. Retry the
            // focus transaction briefly instead of showing an IME that is
            // immediately dismissed by the next window-focus change.
            repeat(8) {
                if (!c.externalKeyboardOpen) return@LaunchedEffect
                if (view.hasWindowFocus()) {
                    focusRequester.requestFocus()
                    kotlinx.coroutines.delay(90)
                    if (c.externalKeyboardOpen && view.hasWindowFocus()) {
                        keyboard?.show()
                        return@LaunchedEffect
                    }
                } else {
                    kotlinx.coroutines.delay(90)
                }
            }
        } else {
            keyboard?.hide()
        }
    }

    // Injecting input into the virtual display can briefly steal window focus
    // from this host window, which hides the soft keyboard mid-typing. When
    // this window gets focus back while the bridge is open, re-focus the
    // field and bring the IME up again. The 300 ms guard stops the listener
    // and the system from fighting over the keyboard in a tight loop.
    DisposableEffect(view) {
        var lastRecoverAt = 0L
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && c.externalKeyboardOpen) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastRecoverAt >= 300L) {
                    lastRecoverAt = now
                    view.post {
                        if (c.externalKeyboardOpen) {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                    }
                }
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }

    Row(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.90f))
            .clickable { focusRequester.requestFocus() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                // Compose locally first. Gboard owns composition, autocorrect,
                // selection and cursor movement; nothing reaches the virtual app
                // until the user explicitly taps SEND.
                value = next
            },
            modifier = Modifier.weight(1f).heightIn(min = 34.dp).focusRequester(focusRequester),
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions.Default,
            maxLines = 2,
            decorationBox = { innerTextField ->
                Box {
                    if (value.text.isEmpty()) Text("Ketik ke aplikasi di layar virtual…", color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp)
                    innerTextField()
                }
            },
        )
        Text(
            "KIRIM",
            Modifier.clip(RoundedCornerShape(8.dp)).background(Deck.Accent)
                .clickable {
                    val payload = value.text
                    if (payload.isNotEmpty() && c.sendText(payload)) {
                        value = TextFieldValue()
                        sentText = ""
                    }
                }.padding(horizontal = 11.dp, vertical = 8.dp),
            color = Color.White, fontSize = 10.sp,
        )
        Text(
            "TUTUP",
            Modifier.clickable {
                keyboard?.hide()
                c.updateExternalKeyboardOpen(false)
            }.padding(5.dp),
            color = Deck.Accent,
            fontSize = 10.sp,
        )
    }
}

private fun forwardTextDelta(previous: String, next: String, c: DeckController, sendInserted: Boolean) {
    var prefix = 0
    while (prefix < previous.length && prefix < next.length && previous[prefix] == next[prefix]) prefix++
    var previousEnd = previous.length
    var nextEnd = next.length
    while (previousEnd > prefix && nextEnd > prefix && previous[previousEnd - 1] == next[nextEnd - 1]) {
        previousEnd--
        nextEnd--
    }
    val removed = previousEnd - prefix
    if (removed > 0) c.sendBackspace(removed)
    val inserted = next.substring(prefix, nextEnd)
    if (sendInserted && inserted.isNotEmpty()) c.sendText(inserted)
}


@Composable
private fun ControlSizeDialog(c: DeckController) {
    val controls = listOf(
        "face_a" to "Tombol A",
        "face_b" to "Tombol B",
        "face_x" to "Tombol X",
        "face_y" to "Tombol Y",
    )
    AlertDialog(
        onDismissRequest = { c.controlSizeMenuOpen = false },
        title = { Text("Ukuran tombol A / B / X / Y") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Atur setiap tombol secara terpisah. Rentang 40–200 dp; perubahan tersimpan otomatis.",
                    fontSize = 11.sp, color = Deck.Ink.copy(alpha = 0.72f))
                controls.forEach { (id, label) ->
                    val value = c.controlSizes[id] ?: 65f
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), color = Deck.Ink, fontSize = 13.sp)
                            Text("${value.toInt()} dp", color = Deck.Accent, fontSize = 12.sp)
                        }
                        Slider(
                            value = value,
                            onValueChange = { c.setControlSize(id, it) },
                            valueRange = 40f..200f,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { c.controlSizeMenuOpen = false }) { Text("SELESAI") }
        },
        dismissButton = {
            TextButton(onClick = { c.resetControlSizes() }) { Text("RESET") }
        },
    )
}
