package com.mlayfer.iptv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** True when the app is running on a TV, where the remote is the only input. */
val LocalIsTv = staticCompositionLocalOf { false }

fun isTelevision(context: Context): Boolean {
    val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val packages = context.packageManager
    if (packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
    // Some boxes report neither of the above. Nothing with no touchscreen is
    // held in a hand, so it is driven by a remote whatever it calls itself —
    // and a keyboard that opens on focus is unusable with one.
    if (!packages.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true
    return context.resources.configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH
}

/**
 * The edge of a television is not the edge of the picture.
 *
 * A TV crops a few percent off every side — overscan, left over from tubes and
 * still there on panels. Anything drawn in that band is simply not on the
 * screen, which is why a title can sit at the top of the layout and be missing
 * in the room. Leanback's guideline is 5% of each side; on a 960dp surface that
 * is 48dp across and 27dp down.
 *
 * One modifier rather than a number copied into each screen: the screen that
 * gets forgotten is the one that gets cropped.
 */
@Composable
fun Modifier.tvSafeArea(): Modifier {
    // Asking the system whether this is a television is a guess that some boxes
    // get wrong, and when it guesses wrong the screen is cropped with no way to
    // tell from here. A wide screen therefore gets the gutter either way: on a
    // television it is what keeps the edges on screen, and anywhere else it is
    // margins, which never hurt anyone.
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    return if (LocalIsTv.current || wide) {
        this.padding(horizontal = 48.dp, vertical = 27.dp)
    } else {
        this
    }
}

/**
 * On a touchscreen the finger says where you are; with a remote, only the
 * highlight does. Every focusable surface needs to show focus, and it has to be
 * visible from across a room.
 */
@Composable
fun Modifier.focusHighlight(
    shape: Shape = RoundedCornerShape(10.dp),
    /** Off for anything that draws its own outline, so it is not ringed twice. */
    border: Boolean = true,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary

    return this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) {
                Modifier
                    .background(accent.copy(alpha = if (border) 0.22f else 0.14f), shape)
                    .then(if (border) Modifier.border(3.dp, accent, shape) else Modifier)
            } else {
                Modifier
            }
        )
}

/**
 * The rule that makes a text field usable with a remote.
 *
 * Compose opens the on-screen keyboard the moment a field takes focus. On a
 * phone that is what you want; on a TV it means the keyboard erupts every time
 * you pass a field on your way down a form. So a field stays read-only until it
 * is actually chosen with OK, and only then asks for the keyboard.
 *
 * A modifier rather than a component, because two fields that look nothing alike
 * need the same behaviour — and a second copy of it is a second thing to forget.
 */
@Composable
fun Modifier.opensOnOk(editing: Boolean, setEditing: (Boolean) -> Unit): Modifier {
    val isTv = LocalIsTv.current
    val keyboard = LocalSoftwareKeyboardController.current

    return this
        .onFocusChanged { focus ->
            if (!focus.isFocused) {
                setEditing(false)
            } else if (isTv && !editing) {
                // Landing on a field is not a request to type: with a remote the
                // keyboard covers the screen, so it waits for OK.
                keyboard?.hide()
            }
        }
        .onKeyEvent { event ->
            if (!isTv || editing) return@onKeyEvent false
            val opens = event.key == Key.Enter ||
                event.key == Key.NumPadEnter ||
                event.key == Key.DirectionCenter
            if (event.type == KeyEventType.KeyUp && opens) {
                setEditing(true)
                true
            } else {
                false
            }
        }
}

/**
 * A text box at the measurements the Tizen build uses.
 *
 * Material's outlined field is 56dp tall whatever it is asked for — two and a
 * half times the Tizen box — which turned the top of every screen into a form.
 * One box, drawn from style.css, under the remote rule above; the two shapes
 * built on it below are the only two this app needs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TzTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val isTv = LocalIsTv.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }

    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        keyboard?.show()
        // A television keyboard is an overlay across the bottom half of the
        // screen, and it does not always tell the app it is there — so a field
        // in the middle of a form ends up behind it, and you type blind. Asking
        // for a tall strip starting at the field forces the panel to scroll it
        // to the top, which is the only place the keyboard never covers.
        bring.bringIntoView(Rect(0f, 0f, 1f, KEYBOARD_ROOM))
    }

    val shape = RoundedCornerShape(tz(14))
    Box(
        modifier = modifier
            .then(if (singleLine) Modifier.height(tz(60)) else Modifier)
            .bringIntoViewRequester(bring)
            .clip(shape)
            .background(Ink.SurfaceLow)
            .border(1.dp, Ink.Line, shape)
            .focusHighlight(shape, border = false)
            .padding(horizontal = tz(16), vertical = tz(12)),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            readOnly = isTv && !editing,
            textStyle = TextStyle(color = Ink.Bright, fontSize = tzSp(22)),
            cursorBrush = SolidColor(Ink.Accent),
            visualTransformation = if (password) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    editing = false
                    keyboard?.hide()
                },
            ),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, fontSize = tzSp(22), color = Ink.Faint, maxLines = 1)
                    }
                    field()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .opensOnOk(editing) { editing = it },
        )
    }
}

/** The box above a shelf. Nothing but a hint inside it. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) = TzTextField(value, onValueChange, modifier, placeholder = placeholder)

/** The box in a form. The label stands above it, as it does on the Tizen panel. */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(tz(8))) {
        Text(label, fontSize = tzSp(18), color = Ink.Dim, maxLines = 1)
        TzTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            password = password,
            singleLine = singleLine,
            minLines = minLines,
        )
    }
}

/**
 * How much room to clear below a field being typed into. Deliberately more than
 * any keyboard needs: the request cannot be satisfied, so the scroll settles
 * with the field at the top, which is exactly what is wanted.
 */
private const val KEYBOARD_ROOM = 3000f
