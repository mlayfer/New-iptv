package com.mlayfer.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import com.mlayfer.iptv.data.Channel
import com.mlayfer.iptv.data.ChannelKind
import com.mlayfer.iptv.data.Series
import com.mlayfer.iptv.ui.AppViewModel
import com.mlayfer.iptv.ui.ChooseScreen
import com.mlayfer.iptv.ui.LocalIsTv
import com.mlayfer.iptv.ui.SourcesScreen
import com.mlayfer.iptv.ui.TelohimTheme
import com.mlayfer.iptv.ui.UiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screens, as pictures.
 *
 * The Android app is the one I cannot look at: it is built in CI and seen only
 * on a television in someone's front room, which is how a row of buttons turned
 * into a row of circles without anyone noticing until a photograph arrived.
 * These render the real composables to PNG on a plain JVM — no emulator — so a
 * change of shape shows up as a change of picture.
 *
 * The pictures live in src/test/screens and are committed, so once they are
 * agreed on, `verifyRoborazziDebug` turns them into a gate: a screen that
 * changes shape without anyone meaning it to fails the build.
 *
 * Recorded with `./gradlew recordRoborazziDebug`, checked with
 * `./gradlew verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A 1080p television, which is the screen this app is mostly looked at on:
// 960x540dp at xhdpi is 1920x1080 pixels. Android fixes the order these
// qualifiers may be written in — size, then ui mode, then density, then
// touchscreen — and Robolectric rejects any other.
@Config(qualifiers = "w960dp-h540dp-television-xhdpi-notouch")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A real view model on Robolectric's application. Its constructor finds an
     * empty store and loads nothing, so it draws whatever state it is handed.
     */
    private val viewModel by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }

    /** Everything the app wraps itself in: the theme, right-to-left, and a TV. */
    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            TelohimTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalIsTv provides true,
                ) {
                    content()
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screens/$name.png")
    }

    private fun catalogue(): UiState {
        val channels = (1..40).map { i ->
            Channel(
                id = "l$i",
                name = if (i <= 4) listOf("כאן 11", "קשת 12", "רשת 13", "ספורט 1")[i - 1] else "ערוץ $i HD",
                url = "http://example.test/$i",
                kind = ChannelKind.LIVE,
                group = listOf("ישראל", "ספורט", "חדשות", "ילדים")[i % 4],
            )
        } + (1..30).map { i ->
            Channel(
                id = "v$i",
                name = "סרט $i",
                url = "http://example.test/v$i",
                kind = ChannelKind.VOD,
                group = listOf("חדש בקולנוע", "אקשן", "קומדיה")[i % 3],
            )
        }
        val series = (1..12).map { i ->
            Series(id = "s$i", name = "סדרה $i", group = "דרמה")
        }
        return UiState(channels = channels, series = series)
    }

    @Test
    fun `the door`() {
        shoot("1-door") {
            ChooseScreen(catalogue(), viewModel)
        }
    }

    @Test
    fun `the sources form`() {
        shoot("2-sources") {
            SourcesScreen(UiState(), viewModel)
        }
    }
}
