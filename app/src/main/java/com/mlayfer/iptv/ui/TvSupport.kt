package com.mlayfer.iptv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
 * A text field that behaves on a remote.
 *
 * Compose opens the on-screen keyboard the moment a field takes focus. On a
 * phone that is what you want; on a TV it means the keyboard erupts every time
 * you pass a field on your way down the form. Here the field stays read-only
 * until it is actually chosen with OK, and only then asks for the keyboard.
 */
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 8,
    shape: Shape = RoundedCornerShape(14.dp),
) {
    val isTv = LocalIsTv.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(editing) {
        if (editing) keyboard?.show()
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = shape,
        textStyle = LocalTextStyle.current,
        readOnly = isTv && !editing,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                editing = false
                keyboard?.hide()
            },
        ),
        // A text field draws its own outline; the highlight only tints it, or the
        // field ends up inside two rings.
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier
            .focusHighlight(shape, border = false)
            .onFocusChanged { focus ->
                if (!focus.isFocused) {
                    editing = false
                } else if (isTv && !editing) {
                    // Landing on a field is not a request to type: with a remote
                    // the keyboard covers the screen, so it waits for OK.
                    keyboard?.hide()
                }
            }
            .onKeyEvent { event ->
                if (!isTv || editing) return@onKeyEvent false
                val opens = event.key == Key.Enter ||
                    event.key == Key.NumPadEnter ||
                    event.key == Key.DirectionCenter
                if (event.type == KeyEventType.KeyUp && opens) {
                    editing = true
                    true
                } else {
                    false
                }
            },
    )
}
