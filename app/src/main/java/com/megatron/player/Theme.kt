package com.megatron.player

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

object PlayerTheme {
    val background = Color(0xFF17191B)
    val colors = darkColorScheme(
        primary = Color(0xFFE6BF7A),
        onPrimary = Color(0xFF211C13),
        secondary = Color(0xFFC5B28F),
        background = background,
        surface = Color(0xFF222527),
        surfaceVariant = Color(0xFF2B2E30),
        onSurfaceVariant = Color(0xFFADAFAE),
        outline = Color(0xFF555958),
        outlineVariant = Color(0xFF34383A),
        onBackground = Color(0xFFF3F1EB),
        onSurface = Color(0xFFF3F1EB),
    )
}
