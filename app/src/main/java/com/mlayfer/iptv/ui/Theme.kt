package com.mlayfer.iptv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The same palette the Tizen build uses: near-black ground, one blue accent that
// doubles as the focus colour, so both apps read as the same product.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4D9BFF),
    onPrimary = Color(0xFF04142E),
    primaryContainer = Color(0xFF16325C),
    onPrimaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFF7CB0FF),
    onSecondary = Color(0xFF04142E),
    background = Color(0xFF0A0A0B),
    onBackground = Color(0xFFE9EEFA),
    surface = Color(0xFF18181B),
    onSurface = Color(0xFFE9EEFA),
    surfaceVariant = Color(0xFF242428),
    onSurfaceVariant = Color(0xFF93A3BF),
    outline = Color(0xFF2E2E33),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0906),
)

@Composable
fun TelohimTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
