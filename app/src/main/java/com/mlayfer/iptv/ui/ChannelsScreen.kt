package com.mlayfer.iptv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.mlayfer.iptv.data.Channel
import com.mlayfer.iptv.data.ChannelKind
import com.mlayfer.iptv.data.Filtering
import com.mlayfer.iptv.data.Playback
import com.mlayfer.iptv.data.XmltvParser

/**
 * The live guide.
 *
 * This used to be a list beside a small player, which is what a media app looks
 * like on a desktop and nothing like what one looks like in a front room. The
 * Tizen build gives the whole screen to the channels — a grid of them, with what
 * is on now above — and opens the player over the top when one is chosen. This
 * is that screen, built out of the same shapes, from Shell.kt.
 */
@Composable
fun ChannelsScreen(state: UiState, viewModel: AppViewModel) {
    var fullscreen by remember { mutableStateOf(false) }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    // Back retraces the way in: out of the player, then out to the door.
    BackHandler(enabled = !fullscreen) { viewModel.back() }
    ImmersiveWhileFullscreen(fullscreen)

    val visible by filteredAsync(
        state.channels, state.query, state.group, state.kind, state.view,
        state.favorites, state.recent,
        initial = emptyList<Channel>(),
        // Nothing typed is nothing to search: answer that one on the spot.
        immediate = state.query.isBlank(),
    ) {
        Filtering.apply(
            channels = state.channels,
            query = state.query,
            group = state.group,
            kind = state.kind,
            favoritesOnly = state.view == ListView.FAVORITES,
            favorites = state.favorites,
            recentOrder = if (state.view == ListView.RECENT) {
                state.recent.map { it.channelId }
            } else {
                null
            },
        )
    }

    /**
     * Moves through the list the viewer is actually in.
     *
     * An episode belongs to its series, not to the catalogue behind it: stepping
     * on from episode three has to reach episode four, not the next series
     * along. Looking an episode up in the channel list never finds it, and
     * falling back to index zero is how the arrows ended up opening whichever
     * series happened to be first.
     */
    fun step(delta: Int) {
        val episodes = state.episodes
        if (state.openSeries != null && episodes.isNotEmpty()) {
            val next = Playback.stepInList(episodes.map { it.id }, state.selectedId.orEmpty(), delta)
            episodes.firstOrNull { it.id == next }?.let(viewModel::select)
            return
        }
        if (visible.isEmpty()) return
        val index = visible.indexOfFirst { it.id == state.selectedId }
        // Zapping stops at the ends of the guide rather than wrapping round,
        // which is what the Tizen build does with the same list.
        val next = (if (index == -1) 0 else index + delta).coerceIn(0, visible.size - 1)
        viewModel.select(visible[next])
    }

    // Zapping is what you do to television. A film is not a channel, and a
    // series is a thing you sit inside, so the arrows must not carry you out of
    // it — and must reach the player's own controls instead.
    // ...though before anything is playing, the channel keys should still start
    // the guide off, the way they always have.
    val zappable = state.selectedChannel?.let { it.kind == ChannelKind.LIVE }
        ?: (state.catalog == Catalog.LIVE)

    DisposableEffect(visible, state.selectedId, fullscreen, zappable) {
        RemoteKeys.setHandler { event ->
            when (event.keyCode) {
                // The channel keys mean channels; on anything else they mean
                // nothing, and swallowing them would only be confusing.
                KeyEvent.KEYCODE_CHANNEL_UP -> if (zappable) { step(1); true } else false
                KeyEvent.KEYCODE_CHANNEL_DOWN -> if (zappable) { step(-1); true } else false

                // The media keys mean "the next thing", which is a channel here
                // and an episode there — step() knows which list it is in.
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    step(1)
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    step(-1)
                    true
                }

                // The D-pad only zaps on the full-screen player, and only for
                // live television; over a film or an episode it has to fall
                // through, or the focus can never reach the controls.
                KeyEvent.KEYCODE_DPAD_UP -> if (fullscreen && zappable) {
                    step(-1)
                    true
                } else {
                    false
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> if (fullscreen && zappable) {
                    step(1)
                    true
                } else {
                    false
                }

                else -> false
            }
        }
        onDispose { RemoteKeys.setHandler(null) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Ink.SurfaceLow) {
        // The player takes the whole screen and no inset padding at all: video
        // uses every pixel, and the system bars are told to get out of the way.
        if (fullscreen) {
            PlayerFor(
                state = state,
                viewModel = viewModel,
                fullscreen = true,
                onToggleFullscreen = { fullscreen = false },
                onPrev = { step(-1) },
                onNext = { step(1) },
                modifier = Modifier.fillMaxSize(),
            )
            return@Surface
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .tvSafeArea()
        ) {
            TopChrome(
                title = "טלוהים",
                tagline = "טלוויזיה בלייב • סרטים • סדרות",
                counts = "${state.channels.size} ערוצים",
            ) {
                NavPill("בית", { viewModel.setScreen(Screen.CHOOSE) })
                NavPill("ערוצים", {}, selected = true)
                NavPill(
                    label = "מועדפים",
                    onClick = { viewModel.setView(nextView(state.view)) },
                    selected = state.view != ListView.ALL,
                )
                NavPill("החלף מקור", { viewModel.setScreen(Screen.SOURCES) })
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = tz(12)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "טלוויזיה בלייב",
                    fontSize = tzSp(32),
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink.Bright,
                )
                Spacer(Modifier.width(tz(24)))
                SearchField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "חיפוש ערוץ",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(tz(20)))
                Faint("${visible.size} ערוצים")
            }

            GroupChips(state, viewModel)

            NowNext(state)

            when {
                state.loading && visible.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                visible.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.error ?: "לא נמצא ערוץ בשם הזה",
                        fontSize = tzSp(22),
                        color = Ink.Dim,
                    )
                }

                // Five across is what the Tizen guide draws, and it is what
                // leaves a channel's logo big enough to know from the sofa.
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(tz(14)),
                    verticalArrangement = Arrangement.spacedBy(tz(14)),
                    contentPadding = PaddingValues(top = tz(12), bottom = tz(28)),
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    itemsIndexed(visible, key = { _, c -> c.id }) { index, channel ->
                        ChannelTile(
                            name = channel.name,
                            // A channel has no number of its own here; where it
                            // sits in the guide is the number people use.
                            meta = listOfNotNull(
                                (index + 1).toString(),
                                channel.group?.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            logo = channel.logo,
                            playing = channel.id == state.selectedId,
                            seen = false,
                            onClick = {
                                viewModel.select(channel)
                                fullscreen = true
                            },
                        )
                    }
                }
            }
        }
    }
}

/** What is on the chosen channel now, and what follows it. */
@Composable
private fun NowNext(state: UiState) {
    val selected = state.selectedChannel ?: return
    val programmes = selected.tvgId?.let { state.epg[it] } ?: return
    val clock = System.currentTimeMillis()
    val onNow = XmltvParser.programmeAt(programmes, clock) ?: return

    NowNextStrip(
        now = onNow.title,
        next = XmltvParser.nextProgramme(programmes, clock)?.title,
        progress = progressOf(onNow.start, onNow.stop, clock),
        modifier = Modifier.padding(vertical = tz(12)),
    )
}

/**
 * All, then favourites, then what was on lately — one button rather than three.
 * The button is lit whenever it is holding something back.
 */
private fun nextView(view: ListView): ListView = when (view) {
    ListView.ALL -> ListView.FAVORITES
    ListView.FAVORITES -> ListView.RECENT
    ListView.RECENT -> ListView.ALL
}

/** How far into a programme the clock has got, between nothing and all of it. */
private fun progressOf(start: Long, stop: Long, now: Long): Float {
    if (stop <= start) return 0f
    return ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/**
 * The categories the portal gave, as a row of chips. "Everything" is always
 * first, because getting back to all of it should not need a search.
 */
@Composable
private fun GroupChips(state: UiState, viewModel: AppViewModel) {
    val groups = remember(state.channels, state.kind) {
        state.channels
            .filter { state.kind == null || it.kind == state.kind }
            .mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }
            .distinct()
    }
    if (groups.isEmpty()) return

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(tz(10)),
        contentPadding = PaddingValues(vertical = tz(12)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            CategoryChip("הכל", state.group == null) { viewModel.setGroup(null) }
        }
        items(groups, key = { it }) { group ->
            CategoryChip(group, state.group == group) { viewModel.setGroup(group) }
        }
    }
}

@Composable
private fun PlayerFor(
    state: UiState,
    viewModel: AppViewModel,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier,
) {
    val selected = state.selectedChannel
    val programmes = selected?.tvgId?.let { state.epg[it] }
    val now = System.currentTimeMillis()

    PlayerPanel(
        channel = selected,
        now = XmltvParser.programmeAt(programmes, now),
        next = XmltvParser.nextProgramme(programmes, now),
        isFavorite = selected != null && state.favorites.contains(selected.id),
        fullscreen = fullscreen,
        onToggleFullscreen = onToggleFullscreen,
        onToggleFavorite = { selected?.let(viewModel::toggleFavorite) },
        onPrev = onPrev,
        onNext = onNext,
        modifier = modifier,
        resumeAt = selected?.let { viewModel.resumeFor(it.id) } ?: 0,
        onProgress = { position, duration ->
            selected?.let { viewModel.noteProgress(it, position, duration) }
        },
        queue = state.episodes,
        onPlayItem = viewModel::select,
    )
}
