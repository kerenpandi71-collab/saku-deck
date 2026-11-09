package com.saku.deck.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Console colors: cream body, gray controls, orange accent. */
object Deck {
    val Body = Color(0xFFE9E4DC)
    val BodyHighlight = Color(0xFFF3EFE9)
    val BodyShade = Color(0xFFD6CFC4)
    val Control = Color(0xFF7A7A7A)
    val ControlDark = Color(0xFF5C5C5C)
    val Accent = Color(0xFFF26B3A)
    val AccentSoft = Color(0xFFFFB27E)
    val Bezel = Color(0xFF2B2A28)
    val ScreenOff = Color(0xFF151413)
    val Ink = Color(0xFF3A3632)
    val Paper = Color(0xFFF7EEE6)

    val ButtonX = Color(0xFF3F6FD8)
    val ButtonY = Color(0xFF3AA55D)
    val ButtonA = Color(0xFFE04848)
    val ButtonB = Color(0xFFE8B830)
}

@Composable
fun SakuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Deck.Accent,
            onPrimary = Color.White,
            background = Deck.Body,
            onBackground = Deck.Ink,
            surface = Deck.Body,
            onSurface = Deck.Ink,
        ),
        content = content,
    )
}
