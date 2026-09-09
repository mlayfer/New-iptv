package com.mlayfer.iptv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7CB0FF),
    onPrimary = Color(0xFF05122A),
    primaryContainer = Color(0xFF1D3A6B),
    onPrimaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFFF2B33D),
    onSecondary = Color(0xFF2A1D02),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFE8EDF7),
    surface = Color(0xFF121B2E),
    onSurface = Color(0xFFE8EDF7),
    surfaceVariant = Color(0xFF1C2740),
    onSurfaceVariant = Color(0xFFB4C1D9),
    outline = Color(0xFF3A4767),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0906),
)

@Composable
fun TelohimTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
