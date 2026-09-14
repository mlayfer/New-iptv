package com.mlayfer.iptv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.mlayfer.iptv.data.Pairing
import kotlinx.coroutines.launch

/**
 * The way in that does not involve typing a password with four arrows.
 *
 * The television puts a form on the local network and shows the address to it
 * as a code. A phone scans the code, types on a keyboard made for typing, and
 * presses send; the form posts straight back here. Nothing goes through a
 * service in the middle, because there is no service in the middle.
 */
@Composable
fun PairWithPhone(
    prefillServer: String,
    onDismiss: () -> Unit,
    onHandoff: (Pairing.Handoff) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        // The handoff arrives on the server's own thread; the screen is only
        // ever touched from this one.
        val session = Pairing.start(prefillServer) { handoff ->
            scope.launch { onHandoff(handoff) }
        }
        if (session == null) offline = true else url = session.url
        onDispose { session?.stop() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = tz(460))
                .clip(RoundedCornerShape(tz(20)))
                .background(Ink.Surface)
                .padding(tz(24)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tz(12)),
        ) {
            Line("התחברות מהטלפון", size = 24, color = Ink.Bright, bold = true)

            val address = url
            when {
                offline -> Line(
                    "אין חיבור רשת, אז אין כתובת להצביע עליה. חבר את הטלוויזיה לרשת ונסה שוב.",
                    size = 18,
                    color = Ink.Faint,
                )

                address == null -> Line("מכין קוד…", size = 18, color = Ink.Faint)

                else -> {
                    Line(
                        "סרוק את הקוד עם מצלמת הטלפון, מלא שם משתמש וסיסמה, ולחץ שלח.",
                        size = 18,
                        color = Ink.Dim,
                    )
                    val code = remember(address) { qrImage(address) }
                    if (code != null) {
                        Image(
                            bitmap = code,
                            contentDescription = "קוד להתחברות מהטלפון",
                            modifier = Modifier
                                .size(tz(260))
                                .clip(RoundedCornerShape(tz(12)))
                                .background(Color.White)
                                .padding(tz(10)),
                        )
                    }
                    // A camera that will not focus, a code photographed at an
                    // angle, a phone with no scanner: the address itself is the
                    // way through, and it is short enough to type.
                    Line(address, size = 18, color = Ink.Bright)
                    Line(
                        "הטלפון צריך להיות על אותה רשת ביתית. הכתובת חיה רק כל עוד החלון פתוח.",
                        size = 16,
                        color = Ink.Faint,
                    )
                }
            }

            val shape = RoundedCornerShape(tz(14))
            Text(
                text = "ביטול",
                fontSize = tzSp(20),
                fontWeight = FontWeight.Bold,
                color = Ink.Bright,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, Ink.Line, shape)
                    .focusHighlight(shape, border = false)
                    .clickable(onClick = onDismiss)
                    .padding(tz(12)),
            )
        }
    }
}

/** Wrapping, centred, and not clipped to one line: these are sentences. */
@Composable
private fun Line(text: String, size: Int, color: Color, bold: Boolean = false) {
    Text(
        text = text,
        fontSize = tzSp(size),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Null rather than a crash: a code that cannot be drawn is not worth an app. */
private fun qrImage(text: String, size: Int = 512): ImageBitmap? = runCatching {
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix[x, y]) BLACK else WHITE
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
