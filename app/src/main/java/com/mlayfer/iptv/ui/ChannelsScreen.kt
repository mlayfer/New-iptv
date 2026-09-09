package com.mlayfer.iptv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.mlayfer.iptv.data.XmltvParser

@Composable
fun ChannelsScreen(state: UiState, viewModel: AppViewModel) {
    var fullscreen by remember { mutableStateOf(false) }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    val visible = remember(
        state.channels, state.query, state.group, state.kind, state.view,
        state.favorites, state.recent,
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

    /** Moves through the list the user is actually looking at, filters included. */
    fun step(delta: Int) {
        if (visible.isEmpty()) return
        val index = visible.indexOfFirst { it.id == state.selectedId }
        val next = if (index == -1) 0 else (index + delta + visible.size) % visible.size
        viewModel.select(visible[next])
    }

    DisposableEffect(visible, state.selectedId, fullscreen) {
        RemoteKeys.setHandler { event ->
            when (event.keyCode) {
                // Dedicated channel keys always zap, wherever focus happens to be.
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    step(1)
                    true
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    step(-1)
                    true
                }

                // The D-pad only zaps on the full-screen player; with the list on
                // screen it has to keep moving the selection as usual.
                KeyEvent.KEYCODE_DPAD_UP -> if (fullscreen) {
                    step(-1)
                    true
                } else {
                    false
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> if (fullscreen) {
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

        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(state, viewModel)

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth >= 720.dp) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        ChannelListPane(
                            state = state,
                            viewModel = viewModel,
                            channels = visible,
                            modifier = Modifier.width(360.dp).fillMaxHeight(),
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
    )
}

@Composable
private fun TopBar(state: UiState, viewModel: AppViewModel) {
    var playlistMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "מסך חי",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 6.dp),
            )

            Box {
                TextButton(onClick = { playlistMenu = true }) {
                    Text(
                        text = state.activePlaylist?.label ?: "בחר רשימה",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(120.dp),
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

            Box(modifier = Modifier.weight(1f))

            IconButton(onClick = { viewModel.refresh() }, enabled = !state.loading) {
                Icon(Icons.Default.Refresh, contentDescription = "רענון")
            }
            IconButton(onClick = { viewModel.setScreen(Screen.SOURCES) }) {
                Icon(Icons.Default.Add, contentDescription = "מקורות תוכן")
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("חיפוש ערוץ או קטגוריה") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

@Composable
private fun ChannelListPane(
    state: UiState,
    viewModel: AppViewModel,
    channels: List<Channel>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ListView.entries.forEach { view ->
                FilterChip(
                    selected = state.view == view,
                    onClick = { viewModel.setView(view) },
                    label = { Text(labelOf(view)) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupPicker(state, viewModel)
            KindPicker(state, viewModel)
        }

        Text(
            text = when {
                state.loading -> "טוען…"
                else -> "${channels.size} ערוצים"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )

        val error = state.error
        when {
            state.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    ChannelRow(
                        channel = channel,
                        selected = channel.id == state.selectedId,
                        favorite = state.favorites.contains(channel.id),
                        nowTitle = channel.tvgId?.let {
                            XmltvParser.programmeAt(state.epg[it], System.currentTimeMillis())?.title
                        },
                        onClick = { viewModel.select(channel) },
                        onToggleFavorite = { viewModel.toggleFavorite(channel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupPicker(state: UiState, viewModel: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    val groups = remember(state.channels, state.kind) {
        M3uParser.groups(
            if (state.kind == null) state.channels else state.channels.filter { it.kind == state.kind }
        )
    }

    Box {
        TextButton(onClick = { open = true }) {
            Text(
                text = state.group ?: "כל הקטגוריות",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(130.dp),
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
private fun KindPicker(state: UiState, viewModel: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    val label = when (state.kind) {
        ChannelKind.LIVE -> "שידור חי"
        ChannelKind.VOD -> "סרטים"
        null -> "הכל"
    }

    Box {
        TextButton(onClick = { open = true }) {
            Text(label, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("הכל") }, onClick = {
                open = false
                viewModel.setKind(null)
            })
            DropdownMenuItem(text = { Text("שידור חי") }, onClick = {
                open = false
                viewModel.setKind(ChannelKind.LIVE)
            })
            DropdownMenuItem(text = { Text("סרטים") }, onClick = {
                open = false
                viewModel.setKind(ChannelKind.VOD)
            })
        }
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
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
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = nowTitle ?: channel.group ?: if (channel.kind == ChannelKind.VOD) "ספריית תוכן" else "שידור חי",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "מועדפים",
                tint = if (favorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun labelOf(view: ListView): String = when (view) {
    ListView.ALL -> "הכל"
    ListView.FAVORITES -> "מועדפים"
    ListView.RECENT -> "אחרונים"
}
