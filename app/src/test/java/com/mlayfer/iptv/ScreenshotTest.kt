package com.mlayfer.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mlayfer.iptv.ui.WhatElseIsOn
import com.mlayfer.iptv.ui.watchListPlacement
import com.mlayfer.iptv.ui.watchPicturePlacement
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
import com.mlayfer.iptv.ui.Catalog
import com.mlayfer.iptv.ui.ChannelsScreen
import com.mlayfer.iptv.ui.ChooseScreen
import com.mlayfer.iptv.ui.GuideLayout
import com.mlayfer.iptv.ui.HomeScreen
import com.mlayfer.iptv.ui.LocalIsTv
import com.mlayfer.iptv.ui.Screen
import com.mlayfer.iptv.ui.SourcesScreen
import com.mlayfer.iptv.ui.TitleScreen
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
    private fun shoot(name: String, content: @Composable () -> Unit) =
        draw(name, tv = true, content)

    /**
     * The same screen in a hand.
     *
     * Every one of these screens was drawn for a 960dp television, and the phone
     * versions were shipped unseen — which is how a title page reached someone
     * with its episode cards at seventy dp and two of its three buttons drawn off
     * the edge of the glass. A picture of the phone costs one more test.
     */
    private fun onAPhone(name: String, content: @Composable () -> Unit) =
        draw(name, tv = false, content)

    private fun draw(name: String, tv: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            TelohimTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalIsTv provides tv,
                ) {
                    content()
                }
            }
        }
        settle()
        compose.onRoot().captureRoboImage("src/test/screens/$name.png")
    }

    /**
     * The lists arrive a moment after the screen does.
     *
     * `filteredAsync` waits out a fast typist and then does the work on a
     * background thread, so a picture taken the instant the screen appears is a
     * picture of an empty catalogue — which tells nobody anything. Advancing the
     * clock releases the wait; the short real sleep lets the pool answer.
     */
    private fun settle() {
        repeat(12) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            Thread.sleep(20)
        }
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

    @Test
    fun `the home rows`() {
        shoot("3-home") {
            HomeScreen(catalogue().copy(screen = Screen.HOME), viewModel)
        }
    }

    @Test
    fun `the live library`() {
        // With a guide, because a tile that says what is on the channel is the
        // point of the tile — and a catalogue with no guide draws a picture of
        // the feature being absent.
        shoot("4-live") {
            ChannelsScreen(guideState().copy(guideLayout = GuideLayout.GRID), viewModel)
        }
    }

    @Test
    fun `the series shelf`() {
        shoot("5-series") {
            HomeScreen(
                catalogue().copy(screen = Screen.HOME, catalog = Catalog.SERIES),
                viewModel,
            )
        }
    }

    // ---- and the same app in a hand ------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the door on a phone`() {
        onAPhone("7-phone-door") { ChooseScreen(catalogue(), viewModel) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the sources form on a phone`() {
        onAPhone("8-phone-sources") { SourcesScreen(UiState(), viewModel) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a series page on a phone`() {
        onAPhone("9-phone-title") {
            TitleScreen(seriesState(), viewModel)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the home rows on a phone`() {
        onAPhone("10-phone-home") {
            HomeScreen(catalogue().copy(screen = Screen.HOME), viewModel)
        }
    }

    private fun seriesState(): UiState {
        val episodes = (1..10).map { i ->
            Channel(
                id = "e$i",
                name = "ניתוק - S01E%02d - פרק".format(i),
                url = "http://example.test/e$i",
                kind = ChannelKind.VOD,
                group = "עונה 1",
            )
        }
        return catalogue().copy(
            screen = Screen.TITLE,
            openSeries = Series(id = "s1", name = "ניתוק (2022)", group = "דרמה"),
            episodes = episodes,
        )
    }

    /**
     * The guide, which is the other half of live television: a wall of logos
     * says what exists, and this says what is on.
     */
    private fun guideState(): UiState {
        val now = System.currentTimeMillis()
        val titles = listOf(
            listOf("מהדורת החדשות", "אולפן שישי", "הסרט של הערב"),
            listOf("ארץ נהדרת", "חדשות הערב", "סדרת דרמה"),
            listOf("משחק הליגה", "מגזין ספורט", "סיכום המחזור"),
            listOf("בוקר טוב ישראל", "תוכנית אירוח", "מהדורה מרכזית"),
        )
        val guide = titles.mapIndexed { index, names ->
            "l${index + 1}" to names.mapIndexed { slot, title ->
                com.mlayfer.iptv.data.Programme(
                    start = now - 1_500_000L + slot * 3_600_000L,
                    stop = now - 1_500_000L + (slot + 1) * 3_600_000L,
                    title = title,
                )
            }
        }.toMap()

        return catalogue().copy(
            screen = Screen.CHANNELS,
            catalog = Catalog.LIVE,
            guideLayout = GuideLayout.VIDEO,
            guide = guide,
            selectedId = "l1",
        )
    }

    /**
     * The channel list over a channel that is still playing.
     *
     * The picture is a stand-in: ExoPlayer does not run on a plain JVM, and it
     * is not what this is a picture of. Everything else is the real screen —
     * the panel, and the placement both it and the player are given, read from
     * the screen itself rather than copied here.
     */
    private fun whileWatching(name: String, tv: Boolean) = draw(name, tv) {
        val watching = guideState()
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            WhatElseIsOn(
                state = watching,
                viewModel = viewModel,
                channels = watching.channels.filter { it.kind == ChannelKind.LIVE },
                clock = System.currentTimeMillis(),
                onPlay = {},
                onFullScreen = {},
                onHighlight = {},
                modifier = watchListPlacement(),
            )
            Box(
                modifier = watchPicturePlacement()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF1B1B1F)),
                contentAlignment = Alignment.Center,
            ) {
                Text("התמונה", color = Color(0xFF6E7A8F))
            }
        }
    }

    @Test
    fun `the channel list over the picture`() {
        whileWatching("13-while-watching", tv = true)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the channel list over the picture on a phone`() {
        whileWatching("14-phone-while-watching", tv = false)
    }

    @Test
    fun `a series page`() {
        shoot("6-title") { TitleScreen(seriesState(), viewModel) }
    }
}
