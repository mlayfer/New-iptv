package com.mlayfer.iptv.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration

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
    MaterialTheme(colorScheme = DarkScheme) {
        // A `Text` that does not name a colour takes LocalContentColor, and
        // Compose's default for that is black — the colour scheme has nothing
        // to do with it. Every screen here paints itself a near-black ground,
        // so an unnamed colour meant black on black: the row headings, the
        // poster captions and the title over the door were all invisible.
        // Naming it once at the root is the whole fix.
        // Tizen's canvas is 1920 wide, so its pixel is a dp on a 960dp
        // television; a phone gets the same proportions drawn larger, because it
        // is read at arm's length and not from a sofa.
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        CompositionLocalProvider(
            LocalContentColor provides DarkScheme.onBackground,
            LocalTzScale provides if (LocalIsTv.current || wide) 0.5f else 0.72f,
            content = content,
        )
    }
}
