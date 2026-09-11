package com.mlayfer.iptv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
// Both lazy lists name their builders the same thing; the column's needs a name
// of its own so the grid below can keep using the grid's.
import androidx.compose.foundation.lazy.itemsIndexed as columnItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import com.mlayfer.iptv.R
import com.mlayfer.iptv.data.Channel
import com.mlayfer.iptv.data.ChannelKind
import com.mlayfer.iptv.data.Filtering
import com.mlayfer.iptv.data.Playback
import com.mlayfer.iptv.data.Programme
import com.mlayfer.iptv.data.XmltvParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

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
    // Watching while you look, rather than a wall of logos.
    val videoMode = state.guideLayout == GuideLayout.VIDEO
    // A schedule is only true for as long as the minute it was drawn in.
    val clock by rememberClock()
    var fullscreen by remember { mutableStateOf(false) }
    // The channel list, open over the picture while it keeps playing. Watching
    // television has always included looking for the next thing to watch, and
    // an app that can only do one at a time makes you leave what you are
    // watching in order to find out what else is on.
    var browsing by remember { mutableStateOf(false) }
    // The channel the cursor is on in that list, which is the one the schedule
    // under the picture is drawn for.
    var highlighted by remember { mutableStateOf<Channel?>(null) }
    // Leaving the player takes the list with it.
    LaunchedEffect(fullscreen) { if (!fullscreen) browsing = false }

    // Back retraces the way in: out of the list, out of the player, out to the
    // door. Only one of these is ever enabled, so their order does not matter.
    BackHandler(enabled = fullscreen && browsing) { browsing = false }
    BackHandler(enabled = fullscreen && !browsing) { fullscreen = false }
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

    DisposableEffect(visible, state.selectedId, fullscreen, browsing, zappable) {
        RemoteKeys.setHandler { event ->
            when (event.keyCode) {
                // The channel keys mean channels; on anything else they mean
                // nothing, and swallowing them would only be confusing.
                KeyEvent.KEYCODE_CHANNEL_UP -> if (zappable) { step(1); true } else false
                KeyEvent.KEYCODE_CHANNEL_DOWN -> if (zappable) { step(-1); true } else false

                // Sideways over a playing channel is what every set-top box
                // means by "what else is on": it opens the list rather than
                // doing nothing. While the list is open the arrows belong to
                // it, so they fall through to ordinary focus movement.
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> if (fullscreen && !browsing && zappable) {
                    browsing = true
                    true
                } else {
                    false
                }

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
                KeyEvent.KEYCODE_DPAD_UP -> if (fullscreen && zappable && !browsing) {
                    step(-1)
                    true
                } else {
                    false
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> if (fullscreen && zappable && !browsing) {
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

    // Filling the screen, or parked in a corner beside the list. The player is
    // called from one place whatever the answer — moving the call somewhere
    // else in the tree would throw the player away and build a new one, which
    // on a live stream means several seconds of black every time.
    val whole = fullscreen && !browsing
    val goFullScreen = {
        browsing = false
        fullscreen = true
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Ink.SurfaceLow) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Full screen the video uses every pixel and the system bars are
                // told to get out of the way; anywhere else the screen keeps its
                // margins.
                .then(
                    if (whole) {
                        Modifier
                    } else {
                        Modifier
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .tvSafeArea()
                    }
                )
        ) {
            if (!whole) TopChrome(
                title = "טלוהים",
                tagline = "טלוויזיה בלייב • סרטים • סדרות",
                counts = "${state.channels.size} ערוצים",
            ) {
                NavPill("בית", { viewModel.setScreen(Screen.CHOOSE) })
                // Five pills and a brand do not fit across a phone, and the row
                // scrolls rather than shrinks — so the one that says where you
                // already are is the one a phone can do without.
                if (isWide) NavPill("ערוצים", {}, selected = true)
                NavPill(
                    label = "מועדפים",
                    onClick = { viewModel.setView(nextView(state.view)) },
                    selected = state.view != ListView.ALL,
                )
                // Two ways to read the same channels, as one switch. Two words
                // took two pills and said the same thing twice; a picture of
                // each says it once and leaves room for the rest of the bar.
                ModeSwitch(
                    first = painterResource(R.drawable.ic_grid),
                    firstLabel = "אריחים",
                    second = painterResource(R.drawable.ic_video_list),
                    secondLabel = "וידאו",
                    onFirst = !videoMode,
                    onPick = { grid ->
                        viewModel.setGuideLayout(
                            if (grid) GuideLayout.GRID else GuideLayout.VIDEO
                        )
                    },
                )
                NavPill("החלף מקור", { viewModel.setScreen(Screen.SOURCES) })
            }

            // In video mode the panel beside the picture carries its own
            // heading and its own categories, and the screen has only so much
            // height: repeating them here is what pushed the schedule under the
            // picture off the bottom of the screen.
            if (!whole && !videoMode) Row(
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

            if (!whole && !videoMode) GroupChips(state, viewModel)

            // The strip above the grid says what is on the channel under the
            // cursor; beside the picture every channel already carries its own.
            if (!whole && !videoMode) NowNext(state, viewModel, clock)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                videoMode || whole || browsing -> Unit

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
                // leaves a channel's logo big enough to know from the sofa. A
                // phone gets two, for the same reason.
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(if (isWide) 5 else 2),
                    horizontalArrangement = Arrangement.spacedBy(tz(14)),
                    verticalArrangement = Arrangement.spacedBy(tz(14)),
                    contentPadding = PaddingValues(top = tz(12), bottom = tz(28)),
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    itemsIndexed(visible, key = { _, c -> c.id }) { index, channel ->
                        // A tile that says only what a channel is called is a
                        // list of names; what is on it is what makes it a guide.
                        // Asked for from here, so only the tiles being looked at
                        // cost a request.
                        LaunchedEffect(channel.id) {
                            delay(250)
                            viewModel.loadGuide(channel)
                        }
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
                            now = XmltvParser
                                .programmeAt(state.programmes(channel), clock)
                                ?.title,
                            onClick = {
                                viewModel.select(channel)
                                fullscreen = true
                            },
                        )
                    }
                }
            }

            // The other mode, and the same thing the full-screen player opens
            // over itself: the picture kept in a corner, the channels beside it.
            if (videoMode || whole || browsing) {
                if (!whole) {
                    WhatElseIsOn(
                        state = state,
                        viewModel = viewModel,
                        channels = visible,
                        clock = clock,
                        onPlay = viewModel::select,
                        onFullScreen = goFullScreen,
                        onHighlight = { highlighted = it },
                        modifier = watchListPlacement(),
                    )
                }

                PlayerFor(
                    state = state,
                    viewModel = viewModel,
                    fullscreen = whole,
                    compact = !whole,
                    // A film has no other channels to flick through; live
                    // television is the only place the list means anything —
                    // including while a stretch of a channel's archive is
                    // playing, which is a recording but still television.
                    onBrowse = if (state.catalog == Catalog.LIVE && fullscreen) {
                        { browsing = !browsing }
                    } else {
                        null
                    },
                    onLeaveList = goFullScreen,
                    onToggleFullscreen = {
                        if (whole) fullscreen = false else goFullScreen()
                    },
                    onPrev = { step(-1) },
                    onNext = { step(1) },
                    modifier = if (whole) Modifier.fillMaxSize() else watchPicturePlacement(),
                    guideChannel = if (whole) null else highlighted,
                )
            }
            }
        }
    }
}

/** What is on the chosen channel now, and what follows it. */
@Composable
private fun NowNext(state: UiState, viewModel: AppViewModel, clock: Long) {
    val selected = state.selectedChannel ?: return
    // The portal answers per channel, so the one under the cursor is the one
    // worth asking about.
    LaunchedEffect(selected.id) { viewModel.loadGuide(selected) }

    val programmes = state.programmes(selected) ?: return
    val onNow = XmltvParser.programmeAt(programmes, clock) ?: return
    val next = XmltvParser.nextProgramme(programmes, clock)

    NowNextStrip(
        now = "${hhmm(onNow.start)} · ${onNow.title}",
        next = next?.let { "אחר כך · ${hhmm(it.start)} ${it.title}" },
        progress = progressOf(onNow.start, onNow.stop, clock),
        modifier = Modifier.padding(vertical = tz(12)),
    )
}

/**
 * How many the schedule under a parked picture has room for. Each entry carries
 * a line of summary as well as its name, so a handful is what fits under a
 * picture on a television that is only so tall.
 */
private const val SCHEDULE_AHEAD = 3

/** How far back the same schedule reaches, where there is an archive to reach into. */
private const val SCHEDULE_BEHIND = 1

/**
 * How much of the channel winding back asks for, and how much more it asks for
 * beyond the live edge so that playing on does not run out of stream.
 */
private const val ARCHIVE_WINDOW = 30
private const val ARCHIVE_RUN_ON = 240

/** How long to ask the archive for. A programme with no end gets an hour. */
private fun minutesOf(programme: Programme): Int {
    val span = programme.stop - programme.start
    if (span <= 0) return 60
    return ((span / 60_000L).toInt()).coerceIn(1, 6 * 60)
}

/**
 * The clock, as something the screen can watch.
 *
 * A schedule drawn once is wrong a minute later: the bar stops moving and a
 * programme that has ended stays on. Half a minute is finer than anyone reads a
 * guide and coarse enough to cost nothing.
 */
@Composable
internal fun rememberClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(30_000)
        value = System.currentTimeMillis()
    }
}

/** A time of day, the way a guide writes one. */
internal fun hhmm(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

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
internal fun progressOf(start: Long, stop: Long, now: Long): Float {
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
    compact: Boolean = false,
    onBrowse: (() -> Unit)? = null,
    /** Shut the channel list, for the things that are not more browsing. */
    onLeaveList: () -> Unit = {},
    onToggleFullscreen: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier,
    /**
     * The channel the schedule is drawn for. The cursor in the list beside the
     * picture, where there is one — what is on the next channel along is the
     * question a list of channels exists to answer.
     */
    guideChannel: Channel? = null,
) {
    val selected = state.selectedChannel
    // Watching a channel is the moment the programme name matters most, so the
    // guide for it is fetched here too rather than only when browsing.
    LaunchedEffect(selected?.id) { selected?.let(viewModel::loadGuide) }

    val clock by rememberClock()
    // The line under the picture says what is playing; the schedule says what is
    // on the channel the cursor is on. They are usually the same channel and
    // sometimes not, and each is answering its own question.
    val watching = state.programmes(selected)
    val guideOf = guideChannel ?: selected
    val programmes = state.programmes(guideOf)

    val onNow = XmltvParser.programmeAt(watching, clock)
    // A channel the portal keeps can be wound back. Most cannot, and the offer
    // is only made where it would work.
    val archive = selected?.takeIf { it.kind == ChannelKind.LIVE && it.archiveDays > 0 }
    val guideArchive = guideOf?.takeIf { it.kind == ChannelKind.LIVE && it.archiveDays > 0 }

    PlayerPanel(
        channel = selected,
        now = onNow,
        next = XmltvParser.nextProgramme(watching, clock),
        scheduleFor = guideOf?.name,
        // What has already been on, what is on now, and the rest of the evening
        // — the same list the guide draws, so the two never disagree. What is
        // behind is only worth listing where it can be played back.
        // Only where there is room for it: a phone's picture already reaches
        // the top of the list, and the list says what is on each channel anyway.
        schedule = if (compact && isWide) {
            val behind = if (guideArchive != null) {
                XmltvParser.alreadyOn(programmes, clock, SCHEDULE_BEHIND)
            } else {
                emptyList()
            }
            behind + listOfNotNull(XmltvParser.programmeAt(programmes, clock)) +
                XmltvParser.upcoming(programmes, clock, SCHEDULE_AHEAD)
        } else {
            emptyList()
        },
        // Catching up acts on the channel whose schedule is being read, which is
        // the one the cursor is on; winding back acts on the picture.
        onCatchUp = guideArchive?.let { channel ->
            { programme: Programme ->
                // Choosing a programme is choosing what to watch, not more
                // browsing: the list goes and the picture comes back to the
                // whole screen. Choosing a channel is the other thing, and that
                // one leaves the list up.
                onLeaveList()
                viewModel.playCatchUp(
                    channel = channel,
                    title = programme.title,
                    startMillis = programme.start,
                    minutes = minutesOf(programme),
                )
            }
        },
        onRewindLive = archive?.let { channel ->
            {
                onLeaveList()
                // Half an hour of the channel, and the playhead dropped just
                // behind the live edge. So the first press is a rewind of a few
                // seconds — which is what a rewind is — and every press after
                // it is an ordinary seek inside an ordinary recording.
                val at = System.currentTimeMillis()
                val from = at - ARCHIVE_WINDOW * 60_000L
                viewModel.playCatchUp(
                    channel = channel,
                    title = XmltvParser.programmeAt(programmes, at)?.title.orEmpty(),
                    startMillis = from,
                    minutes = ARCHIVE_WINDOW + ARCHIVE_RUN_ON,
                    resumeAt = ARCHIVE_WINDOW * 60L - Playback.SEEK_STEP,
                )
            }
        },
        isFavorite = selected != null && state.favorites.contains(selected.id),
        fullscreen = fullscreen,
        compact = compact,
        onBrowse = onBrowse,
        onToggleFullscreen = onToggleFullscreen,
        onToggleFavorite = { selected?.let(viewModel::toggleFavorite) },
        onPrev = onPrev,
        onNext = onNext,
        modifier = modifier,
        // A stretch of archive is not in the history the resume store keeps; it
        // carries where to start with it.
        resumeAt = selected?.let {
            if (it.id == state.adHoc?.id) state.adHocResumeAt else viewModel.resumeFor(it.id)
        } ?: 0,
        onProgress = { position, duration ->
            selected?.let { viewModel.noteProgress(it, position, duration) }
        },
        queue = state.episodes,
        onPlayItem = viewModel::select,
    )
}

/**
 * Where the list goes while a channel plays, and where the picture goes beside
 * it. Written once and read from two places — the screen, and the test that
 * takes its picture — because the arrangement is the part worth looking at and
 * a second copy of it would be a second thing to keep in step.
 */
@Composable
internal fun BoxScope.watchListPlacement(): Modifier = if (isWide) {
    Modifier.align(Alignment.CenterStart).fillMaxWidth(0.49f).fillMaxHeight()
} else {
    // The picture and its strip of controls come to a little under a third of a
    // phone; the list has the rest.
    Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.68f)
}

/**
 * The picture keeps the side the language ends on, so the list it is read
 * alongside starts where the eye does.
 */
@Composable
internal fun BoxScope.watchPicturePlacement(): Modifier = if (isWide) {
    // Wide enough to be worth watching, and no wider: the picture is the top of
    // a column that also holds the controls and the schedule, and a television
    // is only so tall.
    Modifier.align(Alignment.TopEnd).padding(tz(28)).fillMaxWidth(0.40f)
} else {
    Modifier.align(Alignment.TopCenter).padding(top = tz(10))
}

/**
 * The channel list, over a channel that is still playing.
 *
 * This is the one thing a television does that a streaming app forgot: you look
 * for the next thing while the current thing carries on. Choosing here changes
 * the channel and leaves the list up, because nobody finds what they want on
 * the first try — Back is what closes it.
 *
 * The categories run down the side, the way the same list does on the guide
 * screen, so a subscription of thirteen thousand channels is still navigable
 * with four arrows.
 */
@Composable
internal fun WhatElseIsOn(
    state: UiState,
    viewModel: AppViewModel,
    channels: List<Channel>,
    clock: Long,
    onPlay: (Channel) -> Unit,
    /** Give the picture the whole screen and put the list away. */
    onFullScreen: () -> Unit,
    /**
     * Landed on with the remote. The schedule under the picture is drawn for
     * this one rather than for whatever is playing — moving through the list
     * otherwise told you nothing about what you were moving towards, which is
     * the whole question the list is there to answer.
     */
    onHighlight: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // Opening the list has to put the highlight in it, or the arrows that
    // opened it have nothing to move.
    LaunchedEffect(Unit) {
        val at = channels.indexOfFirst { it.id == state.selectedId }
        if (at > 0) listState.scrollToItem(at)
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column(
        modifier = modifier
            .background(Ink.SurfaceLow.copy(alpha = 0.96f))
            .padding(horizontal = tz(20), vertical = tz(16)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "מה עוד משודר",
                fontSize = tzSp(26),
                fontWeight = FontWeight.ExtraBold,
                color = Ink.Bright,
            )
            Spacer(Modifier.weight(1f))
            Faint("${channels.size} ערוצים")
        }

        GroupChips(state, viewModel)

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(tz(8)),
            contentPadding = PaddingValues(bottom = tz(16)),
            // A weight, not fillMaxSize: a child that fills takes every pixel it
            // is offered and leaves the hint below it nowhere to be drawn.
            modifier = Modifier
                .weight(1f)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            columnItemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                LaunchedEffect(channel.id) {
                    delay(250)
                    viewModel.loadGuide(channel)
                }
                val onNow = XmltvParser.programmeAt(state.programmes(channel), clock)
                val playing = channel.id == state.selectedId
                WatchRow(
                    name = channel.name,
                    number = index + 1,
                    logo = channel.logo,
                    now = onNow?.title,
                    range = onNow?.let { "${hhmm(it.start)}–${hhmm(it.stop)}" },
                    playing = playing,
                    // Pressing the one you are already watching is not "watch
                    // it again" — there is nothing else it could mean but
                    // "give it the whole screen".
                    onClick = { if (playing) onFullScreen() else onPlay(channel) },
                    onFocus = { onHighlight(channel) },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }

        // With a remote there is no way to discover what the arrows do except
        // to be told.
        if (LocalIsTv.current) {
            Faint(
                text = "מעלה/מטה — ערוץ · אישור — צפייה · Back — סגירה",
                size = 17,
                modifier = Modifier.padding(top = tz(10)),
            )
        }
    }
}

/**
 * One channel beside a playing picture: narrow, so what it says has to be the
 * two things that identify it — which channel, and what is on it.
 */
@Composable
private fun WatchRow(
    name: String,
    number: Int,
    logo: String?,
    now: String?,
    range: String?,
    playing: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(tz(12))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .background(Ink.Surface)
            .border(1.dp, if (playing) Ink.Accent else Ink.LineSoft, shape)
            .focusHighlight(shape, border = false)
            .clickable(onClick = onClick)
            .padding(horizontal = tz(14), vertical = tz(10)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(tz(84)).height(tz(46)),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = name.take(2),
                    fontSize = tzSp(20),
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink.Accent,
                )
            }
        }
        Spacer(Modifier.width(tz(14)))
        Column(modifier = Modifier.weight(1f)) {
            // Named line heights, or the two lines of a row sit a whole blank
            // line apart and the list reads as twice as long as it is.
            Text(
                text = "$number · $name",
                fontSize = tzSp(20),
                lineHeight = tzSp(24),
                fontWeight = FontWeight.Bold,
                color = Ink.Bright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = now ?: "אין לוח שידורים לערוץ הזה",
                fontSize = tzSp(17),
                lineHeight = tzSp(21),
                color = if (now == null) Ink.Faint else Ink.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (range != null) {
            Spacer(Modifier.width(tz(14)))
            Faint(range, size = 17)
        }
        // Which one is playing has to be readable next to which one is
        // highlighted, and two shades of the same blue are not: the mark says
        // it in a word instead.
        if (playing) {
            Spacer(Modifier.width(tz(14)))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(tz(999)))
                    .background(Ink.Accent)
                    .padding(horizontal = tz(12), vertical = tz(4)),
            ) {
                Text(
                    text = "משודר",
                    fontSize = tzSp(15),
                    fontWeight = FontWeight.Bold,
                    color = Ink.OnAccent,
                    maxLines = 1,
                )
            }
        }
    }
}
