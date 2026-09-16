package com.mlayfer.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What the app has to say for itself after it dies.
 *
 * Shown once, on the launch after a crash, and then forgotten. It is not an
 * apology screen: the only thing on it that matters is the trace, and the only
 * thing it asks is that the trace be sent on.
 */
@Composable
fun CrashScreen(trace: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.SurfaceLow)
            .windowInsetsPadding(WindowInsets.systemBars)
            .tvSafeArea(),
        verticalArrangement = Arrangement.spacedBy(tz(12)),
    ) {
        Text(
            text = "האפליקציה קרסה בפעם הקודמת",
            fontSize = tzSp(26),
            fontWeight = FontWeight.Bold,
            color = Ink.Bright,
        )
        Text(
            text = "זה הדוח. העתק אותו ושלח לי — בלי זה אני מנחש.",
            fontSize = tzSp(18),
            color = Ink.Dim,
        )
        Text(
            text = trace,
            fontSize = tzSp(13),
            // Monospaced, because the thing being read here is a stack trace and
            // the indentation is half of what it says.
            fontFamily = FontFamily.Monospace,
            color = Ink.Dim,
            textAlign = TextAlign.Left,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Ink.Surface, RoundedCornerShape(tz(12)))
                .padding(tz(12))
                .verticalScroll(rememberScrollState()),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(tz(12))) {
            CrashButton("העתק את הדוח", Modifier.weight(1f)) {
                clipboard.setText(AnnotatedString(trace))
            }
            CrashButton("המשך", Modifier.weight(1f), primary = true, onClick = onDismiss)
        }
    }
}

@Composable
private fun CrashButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(tz(14))
    Text(
        text = label,
        fontSize = tzSp(20),
        fontWeight = FontWeight.Bold,
        color = if (primary) Ink.OnAccent else Ink.Bright,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(shape)
            .background(if (primary) Ink.Accent else Ink.Surface)
            .border(1.dp, if (primary) Ink.Accent else Ink.Line, shape)
            .focusHighlight(shape, border = false)
            .clickable(onClick = onClick)
            .padding(tz(14)),
    )
}
