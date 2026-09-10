package com.mlayfer.iptv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mlayfer.iptv.data.Channel
import com.mlayfer.iptv.data.ChannelKind
import com.mlayfer.iptv.data.Filtering
import com.mlayfer.iptv.data.M3uParser
import com.mlayfer.iptv.data.Playback
import com.mlayfer.iptv.data.Series
import com.mlayfer.iptv.data.XmltvParser

private val Gutter = 12.dp

@Composable
fun ChannelsScreen(state: UiState, viewModel: AppViewModel) {
    var fullscreen by remember { mutableStateOf(false) }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = !fullscreen && state.openSeries != null) { viewModel.closeSeries() }
    // Back retraces the way in — to the library's home, or out to the door.
    BackHandler(enabled = !fullscreen && state.openSeries == null) {
        viewModel.back()
    }
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // Full screen deliberately keeps the system bars out of the way and lets
        // the video use every pixel, so it takes no inset padding at all.
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
                .tvSafeArea()
        ) {
            TopBar(state, viewModel)

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    // Without this the last channel sits under the navigation bar.
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                if (maxWidth >= 720.dp) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        ChannelListPane(
                            state = state,
                            viewModel = viewModel,
                            channels = visible,
                            modifier = Modifier.width(if (LocalIsTv.current) 420.dp else 360.dp).fillMaxHeight(),
                        )
                        PlayerFor(
                            state = state,
                            viewModel = viewModel,
                            fullscreen = false,
                            onToggleFullscreen = { fullscreen = true },
                            onPrev = { step(-1) },
                            onNext = { step(1) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        PlayerFor(
                            state = state,
                            viewModel = viewModel,
                            fullscreen = false,
                            onToggleFullscreen = { fullscreen = true },
                            onPrev = { step(-1) },
                            onNext = { step(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ChannelListPane(
                            state = state,
                            viewModel = viewModel,
                            channels = visible,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }
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

@Composable
private fun TopBar(state: UiState, viewModel: AppViewModel) {
    var playlistMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // The bar paints behind the status bar and pads its content out of it.
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { viewModel.back() },
                modifier = Modifier.focusHighlight(),
            ) {
                Text(text = "טלוהים", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(modifier = Modifier.weight(1f)) {
                TextButton(
                    onClick = { playlistMenu = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    Text(
                        text = state.activePlaylist?.label ?: "בחר רשימה",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = playlistMenu, onDismissRequest = { playlistMenu = false }) {
                    state.playlists.forEach { playlist ->
                        DropdownMenuItem(
                            text = { Text(playlist.label, maxLines = 1) },
                            onClick = {
                                playlistMenu = false
                                viewModel.selectPlaylist(playlist.id)
                            },
                        )
                    }
                }
            }

            IconButton(onClick = { viewModel.refresh() }, enabled = !state.loading) {
                Icon(Icons.Default.Refresh, contentDescription = "רענון")
            }
            IconButton(onClick = { viewModel.setScreen(Screen.SOURCES) }) {
                Icon(Icons.Default.Add, contentDescription = "מקורות תוכן")
            }
        }
    }
}

@Composable
private fun ChannelListPane(
    state: UiState,
    viewModel: AppViewModel,
    channels: List<Channel>,
    modifier: Modifier = Modifier,
) {
    val seen = remember(state.recent, state.watched) { state.seen }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        // Search sits with the list it filters, not in the app bar, so the video
        // keeps the top of the screen.
        FormTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = "חיפוש ערוץ או קטגוריה",
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "ניקוי החיפוש")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Gutter, vertical = 10.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Each world offers only its own shelves. Live television has no
            // business advertising films: that separation is the whole point of
            // the door, and a tab row that ignores it undoes it.
            val offered = if (state.catalog == Catalog.LIVE) {
                emptyList()
            } else {
                listOf(Catalog.MOVIES, Catalog.SERIES)
            }
            offered.forEach { catalog ->
                FilterChip(
                    selected = state.view == ListView.ALL && state.catalog == catalog,
                    onClick = {
                        viewModel.setView(ListView.ALL)
                        viewModel.setCatalog(catalog)
                    },
                    label = { Text(labelOf(catalog)) },
                    modifier = Modifier.focusHighlight(border = false),
                )
            }
            // Marked and recently watched are sections of their own, and belong
            // on the same row as the rest of them.
            listOf(ListView.FAVORITES, ListView.RECENT).forEach { view ->
                FilterChip(
                    selected = state.view == view,
                    onClick = { viewModel.setView(view) },
                    label = { Text(labelOf(view)) },
                    modifier = Modifier.focusHighlight(border = false),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Gutter - 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupPicker(state, viewModel)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = when {
                    state.loading -> "טוען…"
                    state.catalog == Catalog.SERIES && state.openSeries == null -> "${state.series.size}"
                    else -> "${channels.size}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        if (state.notes.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gutter, vertical = 4.dp),
            ) {
                state.notes.forEach { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        val error = state.error
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            error != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = error, color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = { viewModel.refresh() },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text("נסה שוב")
                }
            }

            state.catalog == Catalog.SERIES -> SeriesContent(state, viewModel)

            channels.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (state.view) {
                        ListView.FAVORITES -> "עוד לא סימנת ערוצים מועדפים"
                        ListView.RECENT -> "כאן יופיעו הערוצים שצפית בהם"
                        ListView.ALL -> "אין ערוצים שתואמים לסינון"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(channels, key = { it.id }) { channel ->
                    // A film is a thing you finish; a channel is not, so only
                    // one of them is offered a tick.
                    val film = channel.kind == ChannelKind.VOD
                    ChannelRow(
                        channel = channel,
                        selected = channel.id == state.selectedId,
                        favorite = state.favorites.contains(channel.id),
                        nowTitle = channel.tvgId?.let {
                            XmltvParser.programmeAt(state.epg[it], System.currentTimeMillis())?.title
                        },
                        onClick = { viewModel.select(channel) },
                        onToggleFavorite = { viewModel.toggleFavorite(channel) },
                        seen = if (film) channel.id in seen else null,
                        onToggleWatched = if (film) {
                            { viewModel.toggleWatched(channel.id) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesContent(state: UiState, viewModel: AppViewModel) {
    val open = state.openSeries
    // Walking the marks is cheap but not free, and this runs on every frame
    // otherwise; it only changes when the memory does.
    val seen = remember(state.recent, state.watched) { state.seen }

    if (open == null) {
        val filtered by filteredAsync(
            state.series, state.query, state.group,
            initial = emptyList<Series>(),
            immediate = state.query.isBlank(),
        ) {
            val needle = Filtering.normalize(state.query.trim())
            state.series.filter { series ->
                val group = series.group?.trim().takeUnless { it.isNullOrEmpty() } ?: M3uParser.NO_GROUP
                (state.group == null || group == state.group) &&
                    (
                        needle.isEmpty() ||
                            Filtering.normalize(series.name).contains(needle) ||
                            Filtering.normalize(series.group ?: "").contains(needle)
                        )
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.series.isEmpty()) {
                        "המקור הזה לא מספק סדרות. רשימות M3U מגישות סדרות כקבצים בודדים — הן יופיעו תחת \"סרטים\"."
                    } else {
                        "אין סדרות שתואמות לסינון"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { series ->
                SeriesRow(series) { viewModel.openSeries(series) }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.closeSeries() }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.closeSeries() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה לרשימת הסדרות")
            }
            Text(
                text = open.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // A season is ticked off as one gesture: all of it, or none of it.
            if (state.episodes.isNotEmpty()) {
                val done = state.episodes.all { it.id in seen }
                TextButton(
                    onClick = { viewModel.toggleWatchedAll(state.episodes.map { it.id }) },
                    modifier = Modifier.focusHighlight(),
                ) { Text(if (done) "סמן כלא נצפתה" else "סמן הכל כנצפה") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        val episodesError = state.episodesError
        when {
            state.episodesLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            episodesError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = episodesError, color = MaterialTheme.colorScheme.error)
            }

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.episodes, key = { it.id }) { episode ->
                    ChannelRow(
                        channel = episode,
                        selected = episode.id == state.selectedId,
                        favorite = state.favorites.contains(episode.id),
                        nowTitle = null,
                        onClick = { viewModel.select(episode) },
                        onToggleFavorite = { viewModel.toggleFavorite(episode) },
                        seen = episode.id in seen,
                        onToggleWatched = { viewModel.toggleWatched(episode.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupPicker(state: UiState, viewModel: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    val groups = remember(state.channels, state.series, state.catalog) {
        if (state.catalog == Catalog.SERIES) {
            state.series
                .groupingBy { it.group?.trim().takeUnless { g -> g.isNullOrEmpty() } ?: M3uParser.NO_GROUP }
                .eachCount()
                .toList()
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        } else {
            val kind = state.kind
            M3uParser.groups(
                if (kind == null) state.channels else state.channels.filter { it.kind == kind }
            )
        }
    }

    Box {
        TextButton(onClick = { open = true }) {
            Text(
                text = state.group ?: "כל הקטגוריות",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(max = 130.dp),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("כל הקטגוריות") },
                onClick = {
                    open = false
                    viewModel.setGroup(null)
                },
            )
            groups.forEach { (name, count) ->
                DropdownMenuItem(
                    text = { Text("$name ($count)", maxLines = 1) },
                    onClick = {
                        open = false
                        viewModel.setGroup(name)
                    },
                )
            }
        }
    }
}

@Composable
private fun SeriesRow(series: Series, onClick: () -> Unit) {
    val isTv = LocalIsTv.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isTv) 84.dp else 68.dp)
            .focusHighlight()
            .clickable(onClick = onClick)
            .padding(horizontal = Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (isTv) 56.dp else 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (series.logo != null) {
                AsyncImage(
                    model = series.logo,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = series.name,
                style = if (isTv) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = series.group ?: "סדרה",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    selected: Boolean,
    favorite: Boolean,
    nowTitle: String?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    /** Null for a channel: live television is not a thing you finish. */
    seen: Boolean? = null,
    onToggleWatched: (() -> Unit)? = null,
) {
    val isTv = LocalIsTv.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isTv) 84.dp else 68.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .focusHighlight()
            .clickable(onClick = onClick)
            .padding(horizontal = Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (isTv) 56.dp else 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logo != null) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = channel.name,
                style = if (isTv) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = if (seen == true) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color.Unspecified
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (seen == true) "נצפה" else nowTitle ?: channel.group
                    ?: if (channel.kind == ChannelKind.VOD) "ספריית תוכן" else "שידור חי",
                style = if (isTv) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.labelSmall
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (onToggleWatched != null) {
            IconButton(onClick = onToggleWatched) {
                // The same tick either way: lit when it is done, faint when it
                // is an invitation to tick it.
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = if (seen == true) "סמן כלא נצפה" else "סמן כנצפה",
                    tint = if (seen == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
        }

        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "מועדפים",
                tint = if (favorite) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun labelOf(view: ListView): String = when (view) {
    ListView.ALL -> "הכל"
    ListView.FAVORITES -> "מועדפים"
    ListView.RECENT -> "אחרונים"
}

private fun labelOf(catalog: Catalog): String = when (catalog) {
    Catalog.ALL -> "הכל"
    Catalog.LIVE -> "שידור חי"
    Catalog.MOVIES -> "סרטים"
    Catalog.SERIES -> "סדרות"
}
