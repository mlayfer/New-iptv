package com.mlayfer.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mlayfer.iptv.data.HomeRows

/**
 * The screen the app opens on. Not a menu of places to go: what you were
 * watching, what you marked, and then the catalogue — the same rows, in the
 * same order, as the Tizen build, because HomeRows decides them for both.
 */
@Composable
fun HomeScreen(state: UiState, viewModel: AppViewModel) {
    val home = remember(state.channels, state.series, state.recent, state.favorites) {
        state.homeRows
    }
    var query by remember { mutableStateOf("") }
    // One box over the whole catalogue: a title is not filed under the section
    // you happen to be standing in. Same rule as the Tizen build, from HomeRows.
    val rows = remember(home, query, state.channels, state.series) {
        if (query.trim().length < 2) home else HomeRows.search(state.homeCards, query)
    }
    var highlighted by remember { mutableStateOf<HomeRows.Card?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        HomeTopBar(state, viewModel)
        CatalogSummary(state)

        FormTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "חיפוש ערוץ, סרט או סדרה",
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val focus = highlighted ?: rows.firstOrNull()?.items?.firstOrNull()
        if (focus != null) HomeHero(focus, viewModel.resumeFor(focus.id))

        when {
            state.loading && rows.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            rows.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        query.trim().length >= 2 -> "לא נמצא כלום בשם הזה"
                        state.error != null -> state.error
                        else -> "אין תוכן להצגה"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    CardRow(
                        row = row,
                        favorites = state.favorites,
                        onHighlight = { highlighted = it },
                        onOpen = viewModel::openCard,
                        onFavorite = { viewModel.toggleFavoriteId(it.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(state: UiState, viewModel: AppViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "טלוהים",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(16.dp))
        TextButton(
            onClick = {
                viewModel.setCatalog(Catalog.LIVE)
                viewModel.setScreen(Screen.CHANNELS)
            },
            modifier = Modifier.focusHighlight(),
        ) { Text("ערוצים") }
        TextButton(
            onClick = {
                viewModel.setCatalog(Catalog.MOVIES)
                viewModel.setScreen(Screen.CHANNELS)
            },
            modifier = Modifier.focusHighlight(),
        ) { Text("סרטים") }
        TextButton(
            onClick = {
                viewModel.setCatalog(Catalog.SERIES)
                viewModel.setScreen(Screen.CHANNELS)
            },
            modifier = Modifier.focusHighlight(),
        ) { Text("סדרות") }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { viewModel.setScreen(Screen.SOURCES) },
            modifier = Modifier.focusHighlight(),
        ) { Text("מקורות") }
    }
}

/**
 * How much of each catalogue arrived, and what did not. A title someone expects
 * and cannot find is either missing from the portal or lost on the way in, and
 * a count is the difference between the two.
 */
@Composable
private fun CatalogSummary(state: UiState) {
    val cards = state.homeCards
    if (cards.isEmpty()) return
    val live = cards.count { it.kind == "LIVE" }
    val series = cards.count { it.contentType == "SERIES" }
    val movies = cards.size - live - series
    val counts = "$live ערוצים · $movies סרטים · $series סדרות"
    val notes = state.notes.joinToString(" · ")

    Text(
        text = if (notes.isBlank()) counts else "$counts · $notes",
        style = MaterialTheme.typography.bodySmall,
        color = if (state.notes.isEmpty()) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** What sits under the cursor, said out loud so the row titles can stay short. */
@Composable
private fun HomeHero(card: HomeRows.Card, resumeAt: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = describe(card, resumeAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Art(
            logo = card.logo,
            name = card.name,
            modifier = Modifier
                .width(if (card.kind == "LIVE") 108.dp else 62.dp)
                .height(62.dp),
            crop = card.kind != "LIVE",
        )
    }
}

private fun describe(card: HomeRows.Card, resumeAt: Long): String {
    val what = when {
        card.kind == "LIVE" -> "שידור חי"
        card.contentType == "SERIES" -> "סדרה"
        else -> "סרט"
    }
    val resume = if (resumeAt > 0) " · המשך מ-" + clock(resumeAt) else ""
    return "$what · ${card.group}$resume"
}

private fun clock(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun CardRow(
    row: HomeRows.Row,
    favorites: Set<String>,
    onHighlight: (HomeRows.Card) -> Unit,
    onOpen: (HomeRows.Card) -> Unit,
    onFavorite: (HomeRows.Card) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(row.items, key = { it.id }) { card ->
                ContentCard(
                    card = card,
                    isFavorite = card.id in favorites,
                    onHighlight = onHighlight,
                    onOpen = onOpen,
                    onFavorite = onFavorite,
                )
            }
        }
    }
}

/** A channel is a wide card and a film is a poster: the shape says what it is. */
@Composable
private fun ContentCard(
    card: HomeRows.Card,
    isFavorite: Boolean,
    onHighlight: (HomeRows.Card) -> Unit,
    onOpen: (HomeRows.Card) -> Unit,
    onFavorite: (HomeRows.Card) -> Unit,
) {
    val live = card.kind == "LIVE"
    Column(
        modifier = Modifier
            .width(if (live) 160.dp else 108.dp)
            .focusHighlight(RoundedCornerShape(12.dp))
            .clickable { onHighlight(card); onOpen(card) }
            .padding(4.dp)
    ) {
        Box {
            Art(
                logo = card.logo,
                name = card.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (live) 90.dp else 150.dp),
                crop = !live,
            )
            if (isFavorite) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = "במועדפים",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(16.dp),
                )
            }
        }
        if (card.progress > 0) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(card.progress.toFloat())
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Text(
            text = card.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Artwork when the portal gives any, initials when it does not. */
@Composable
private fun Art(logo: String?, name: String, modifier: Modifier, crop: Boolean) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(if (crop) 0.dp else 8.dp),
            )
        } else {
            Text(
                text = name.take(2).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
