package com.mlayfer.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mlayfer.iptv.data.Channel
import com.mlayfer.iptv.data.XmltvParser

/**
 * The page a title gets before it is played.
 *
 * The Android app never had one: a film or a series went straight from a list
 * into a player, which is why a series behaved like a channel — there was
 * nowhere to stand and decide. This is the Tizen title page: what it is, what it
 * is about, the two or three things you can do with it, and, for a series, its
 * episodes as cards rather than a strip of text.
 */
@Composable
fun TitleScreen(state: UiState, viewModel: AppViewModel) {
    var playing by remember { mutableStateOf(false) }
    BackHandler(enabled = playing) { playing = false }
    BackHandler(enabled = !playing) {
        viewModel.closeSeries()
        viewModel.setScreen(Screen.HOME)
    }
    ImmersiveWhileFullscreen(playing)

    val series = state.openSeries
    val chosen = state.selectedChannel
    val title = series?.name ?: chosen?.name ?: return
    val art = series?.logo ?: chosen?.logo

    Surface(modifier = Modifier.fillMaxSize(), color = Ink.SurfaceLow) {
        if (playing) {
            val programmes = chosen?.tvgId?.let { state.epg[it] }
            val clock = System.currentTimeMillis()
            PlayerPanel(
                channel = chosen,
                now = XmltvParser.programmeAt(programmes, clock),
                next = XmltvParser.nextProgramme(programmes, clock),
                isFavorite = chosen != null && chosen.id in state.favorites,
                fullscreen = true,
                onToggleFullscreen = { playing = false },
                onToggleFavorite = { chosen?.let(viewModel::toggleFavorite) },
                onPrev = { stepEpisode(state, viewModel, -1) },
                onNext = { stepEpisode(state, viewModel, 1) },
                modifier = Modifier.fillMaxSize(),
                resumeAt = chosen?.let { viewModel.resumeFor(it.id) } ?: 0,
                onProgress = { position, duration ->
                    chosen?.let { viewModel.noteProgress(it, position, duration) }
                },
                queue = state.episodes,
                onPlayItem = viewModel::select,
            )
            return@Surface
        }

        val seasons = remember(state.episodes) {
            state.episodes.mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }.distinct()
        }
        var season by remember(seasons) { mutableStateOf(seasons.firstOrNull()) }
        val episodes = remember(state.episodes, season) {
            if (season == null) state.episodes else state.episodes.filter { it.group == season }
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
                counts = null,
            ) {
                NavPill("בית", {
                    viewModel.closeSeries()
                    viewModel.setScreen(Screen.HOME)
                })
                NavPill("החלף מקור", { viewModel.setScreen(Screen.SOURCES) })
            }

            Banner(
                title = title,
                meta = metaLine(state, series != null),
                art = art,
                seen = (series?.id?.let { "series:$it" } ?: chosen?.id).orEmpty() in state.seen,
            ) {
                val first = state.episodes.firstOrNull()
                DetailPill(
                    label = playLabel(state, viewModel, series != null),
                    primary = true,
                    onClick = {
                        val target = if (series != null) first else chosen
                        target?.let { viewModel.select(it); playing = true }
                    },
                )
                DetailPill(
                    label = if ((chosen?.id ?: "") in state.favorites) "הסר ממועדפים" else "הוסף למועדפים",
                    onClick = {
                        val id = series?.let { "series:${it.id}" } ?: chosen?.id
                        id?.let(viewModel::toggleFavoriteId)
                    },
                )
                if (episodes.isNotEmpty()) {
                    DetailPill(
                        label = "סמן את כל העונה כנצפתה",
                        onClick = { viewModel.toggleWatchedAll(episodes.map { it.id }) },
                    )
                }
            }

            when {
                state.episodesLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.episodesError != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text(state.episodesError, fontSize = tzSp(22), color = Ink.Dim) }

                episodes.isEmpty() -> Spacer(Modifier.fillMaxSize())

                else -> {
                    val grid: @Composable (Modifier) -> Unit = { mod ->
                        LazyVerticalGrid(
                            // Five across is a television. Two is a phone. The
                            // same five squeezed into a hand is what turned an
                            // episode card into a stamp with "נ..." on it.
                            columns = GridCells.Fixed(if (isWide) 5 else 2),
                            horizontalArrangement = Arrangement.spacedBy(tz(14)),
                            verticalArrangement = Arrangement.spacedBy(tz(14)),
                            contentPadding = PaddingValues(bottom = tz(28)),
                            modifier = mod,
                        ) {
                            itemsIndexed(episodes, key = { _, e -> e.id }) { index, episode ->
                                EpisodeCard(
                                    episode = episode,
                                    number = index + 1,
                                    seen = episode.id in state.seen,
                                    onClick = { viewModel.select(episode); playing = true },
                                )
                            }
                        }
                    }

                    if (isWide) {
                        // Seasons stand on the right, where a hand reaches first
                        // with a remote in it, and the episodes fill the rest.
                        Row(modifier = Modifier.fillMaxSize().padding(top = tz(16))) {
                            if (seasons.size > 1) {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(tz(10)),
                                    modifier = Modifier.width(tz(220)).fillMaxHeight(),
                                ) {
                                    items(seasons, key = { it }) { name ->
                                        SeasonPill(name, name == season) { season = name }
                                    }
                                }
                                Spacer(Modifier.width(tz(20)))
                            }
                            grid(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        // A phone has no width to give away to a sidebar, so the
                        // seasons become a row above the episodes.
                        Column(modifier = Modifier.fillMaxSize().padding(top = tz(16))) {
                            if (seasons.size > 1) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(tz(10)),
                                    contentPadding = PaddingValues(bottom = tz(14)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    items(seasons, key = { it }) { name ->
                                        CategoryChip(name, name == season) { season = name }
                                    }
                                }
                            }
                            grid(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/** The next or previous episode, when the player asks for one. */
private fun stepEpisode(state: UiState, viewModel: AppViewModel, delta: Int) {
    val episodes = state.episodes
    if (episodes.isEmpty()) return
    val index = episodes.indexOfFirst { it.id == state.selectedId }
    if (index == -1) return
    episodes.getOrNull(index + delta)?.let(viewModel::select)
}

private fun metaLine(state: UiState, isSeries: Boolean): String {
    val what = if (isSeries) "סדרה" else "סרט"
    val group = state.openSeries?.group ?: state.selectedChannel?.group
    val episodes = if (isSeries && state.episodes.isNotEmpty()) {
        "${state.episodes.size} פרקים"
    } else {
        null
    }
    return listOfNotNull(what, group?.takeIf { it.isNotBlank() }, episodes).joinToString(" · ")
}

private fun playLabel(state: UiState, viewModel: AppViewModel, isSeries: Boolean): String {
    val id = state.selectedChannel?.id ?: state.episodes.firstOrNull()?.id
    val resume = id?.let(viewModel::resumeFor) ?: 0
    if (resume > 0) return "המשך לצפות"
    return if (isSeries) "צפה בפרק הראשון" else "צפה"
}

/**
 * The band a title arrives on: its artwork washed across the back, its name
 * said large, and the two or three things worth doing with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Banner(
    title: String,
    meta: String,
    art: String?,
    seen: Boolean,
    actions: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(tz(18))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // A television gets a band of a fixed height. A phone gets a floor
            // and no ceiling: its three buttons wrap onto a second line, and a
            // fixed height simply cuts that line off — which is how two of them
            // went missing rather than moving.
            .then(if (isWide) Modifier.height(tz(400)) else Modifier.heightIn(min = tz(300)))
            .padding(top = tz(12))
            .clip(shape)
            .background(Ink.SurfaceLow)
            .border(1.dp, Ink.LineSoft, shape),
    ) {
            // matchParentSize, not fillMaxSize: a child that fills takes the
            // whole height it is offered and drags the band with it, which on a
            // phone — where the band has no fixed height — swallowed the screen.
            // This one measures after the others and never votes on the size.
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.30f,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Ink.SurfaceLow.copy(alpha = 0.55f), Ink.SurfaceLow.copy(alpha = 0.96f))
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isWide) Modifier.fillMaxHeight() else Modifier)
                .padding(tz(30)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The poster stands at the right-hand edge, which in this direction
            // is where the eye starts.
            Box(
                modifier = Modifier
                    // Filling the height needs a height to fill; on a phone the
                    // band has none of its own, so the poster names one.
                    .then(if (isWide) Modifier.fillMaxHeight() else Modifier.height(tz(240)))
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(tz(12)))
                    .background(Brush.linearGradient(listOf(Color(0xFF232327), Color(0xFF111113))))
                    .border(1.dp, Ink.LineSoft, RoundedCornerShape(tz(12))),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = title.take(2),
                        fontSize = tzSp(52),
                        fontWeight = FontWeight.ExtraBold,
                        color = Ink.Accent,
                    )
                }
                if (seen) SeenTick(Modifier.align(Alignment.TopStart).padding(tz(8)))
            }
            Spacer(Modifier.width(tz(30)))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    fontSize = if (isWide) tzSp(48) else tzSp(38),
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink.Bright,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
                Text(
                    text = meta,
                    fontSize = tzSp(22),
                    color = Ink.Dim,
                    modifier = Modifier.padding(top = tz(10)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
                // Three pills do not fit across a phone, and a Row does not care:
                // it simply draws the last two off the edge of the screen, which
                // is how "add to favourites" stopped existing there.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(tz(14)),
                    verticalArrangement = Arrangement.spacedBy(tz(10)),
                    modifier = Modifier.padding(top = tz(20)),
                ) { actions() }
            }
        }
    }
}

/** An action on a title page: a pill, filled when it is the obvious one. */
@Composable
private fun DetailPill(label: String, onClick: () -> Unit, primary: Boolean = false) {
    val shape = RoundedCornerShape(tz(26))
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (primary) Ink.Accent else Ink.Surface)
            .border(1.dp, if (primary) Ink.Accent else Ink.Line, shape)
            .focusHighlight(shape, border = false)
            .clickable(onClick = onClick)
            .padding(horizontal = tz(28), vertical = tz(16)),
    ) {
        Text(
            text = label,
            fontSize = tzSp(22),
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
            color = if (primary) Ink.OnAccent else Ink.Bright,
            maxLines = 1,
        )
    }
}

@Composable
private fun SeasonPill(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(tz(24))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (active) Ink.Accent else Ink.Surface)
            .border(1.dp, if (active) Ink.Accent else Ink.Line, shape)
            .focusHighlight(shape, border = false)
            .clickable(onClick = onClick)
            .padding(vertical = tz(14)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = tzSp(21),
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) Ink.OnAccent else Ink.Bright,
            maxLines = 1,
        )
    }
}

/**
 * One episode. A card with its own artwork rather than a line in a list — which
 * is what a strip of twenty titles turns into on a television across the room.
 */
@Composable
private fun EpisodeCard(episode: Channel, number: Int, seen: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(tz(14))
    Column(
        modifier = Modifier
            .clip(shape)
            .background(Ink.Surface)
            .border(1.dp, Ink.LineSoft, shape)
            .focusHighlight(shape, border = false)
            .clickable(onClick = onClick)
            .padding(tz(10)),
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(tz(10)))
                .background(Brush.linearGradient(listOf(Color(0xFF232327), Color(0xFF111113)))),
            contentAlignment = Alignment.Center,
        ) {
            if (episode.logo != null) {
                AsyncImage(
                    model = episode.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = if (seen) 0.45f else 1f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(tz(8))
                    .clip(RoundedCornerShape(tz(8)))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = tz(10), vertical = tz(4)),
            ) {
                Text("פרק $number", fontSize = tzSp(17), color = Ink.Bright, maxLines = 1)
            }
            if (seen) SeenTick(Modifier.align(Alignment.TopEnd).padding(tz(8)))
        }
        Text(
            text = episode.name,
            fontSize = tzSp(19),
            color = if (seen) Ink.Faint else Ink.Bright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = tz(10)).fillMaxWidth(),
            textAlign = TextAlign.End,
        )
        Faint(episode.group.orEmpty(), Modifier.fillMaxWidth(), size = 17)
    }
}
