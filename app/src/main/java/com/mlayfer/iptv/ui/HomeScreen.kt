package com.mlayfer.iptv.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mlayfer.iptv.data.HomeRows

/**
 * The screen the library opens on. Not a menu of places to go: what you were
 * watching, what you marked, and then the catalogue — the same rows, in the same
 * order, as the Tizen build, because HomeRows decides them for both.
 *
 * It is now built out of the same shapes too. The rows, the band across the top
 * and the bar above it come from Shell.kt, which is the Tizen stylesheet written
 * in Compose — so the two are one product rather than two apps that merely agree
 * about their data.
 */
@Composable
fun HomeScreen(state: UiState, viewModel: AppViewModel) {
    // The library's home is one step in from the door, not the way out.
    BackHandler { viewModel.setScreen(Screen.CHOOSE) }

    // Building the cards walks the whole catalogue — nineteen thousand of them
    // on a real subscription — so it happens when the catalogue changes and not
    // once a frame.
    val cards = remember(state.channels, state.series, state.recent) { state.homeCards }
    val home = remember(cards, state.recent, state.favorites) { state.homeRows }
    var query by remember { mutableStateOf("") }
    // One box over the whole catalogue: a title is not filed under the section
    // you happen to be standing in. Same rule as the Tizen build, from HomeRows.
    // Twenty thousand cards are walked for every letter; not on the thread that
    // draws the screen.
    val searching = query.trim().length >= 2
    val found by filteredAsync(
        home, query, cards, state.searchIndex,
        initial = home,
        immediate = !searching,
    ) { if (searching) HomeRows.search(cards, query, index = state.searchIndex) else home }

    // "Films" and "Series" are one place with the home shelf, not a different
    // screen — but they are not the home shelf filtered. Home keeps twenty of
    // each kind because it is a summary; a section is the list, and a list that
    // stops at twenty out of three hundred is a bug rather than a summary. So a
    // section is built from the whole catalogue, grouped by the category the
    // portal gave it, which is exactly what the Tizen build draws.
    val rows = remember(found, cards, state.catalog, searching) {
        if (searching || state.catalog == Catalog.ALL) {
            forCatalog(found, state.catalog)
        } else {
            sectionRows(cards, state.catalog)
        }
    }

    var highlighted by remember { mutableStateOf<HomeRows.Card?>(null) }
    val seen = remember(state.recent, state.watched) { state.seen }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .tvSafeArea()
    ) {
        TopChrome(
            title = "טלוהים",
            tagline = "טלוויזיה בלייב • סרטים • סדרות",
            counts = summary(cards, state.notes),
        ) {
            NavPill("בית", { viewModel.setCatalog(Catalog.ALL) }, selected = state.catalog == Catalog.ALL)
            NavPill("סרטים", { viewModel.setCatalog(Catalog.MOVIES) }, selected = state.catalog == Catalog.MOVIES)
            NavPill("סדרות", { viewModel.setCatalog(Catalog.SERIES) }, selected = state.catalog == Catalog.SERIES)
            NavPill("החלף מקור", { viewModel.setScreen(Screen.SOURCES) })
        }

        // The band says what is under the cursor. With a finger there is no
        // cursor — it simply showed whatever happened to be first, with its name
        // cut off, and cost a third of a phone screen to do it.
        val focus = if (isWide) highlighted ?: rows.firstOrNull()?.items?.firstOrNull() else null
        if (focus != null) {
            HeroBand(
                title = focus.name,
                kicker = focus.group,
                meta = describe(focus, viewModel.resumeFor(focus.id)),
                art = focus.logo,
                modifier = Modifier.padding(top = tz(12)),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = tz(14)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "חיפוש ערוץ, סרט או סדרה",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(tz(20)))
            Faint("${rows.sumOf { it.items.size }} פריטים")
        }

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
                        searching -> "לא נמצא כלום בשם הזה"
                        state.error != null -> state.error
                        else -> "אין תוכן להצגה"
                    },
                    fontSize = tzSp(22),
                    color = Ink.Dim,
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = tz(28)),
                verticalArrangement = Arrangement.spacedBy(tz(10)),
            ) {
                items(rows, key = { it.key }) { row ->
                    CardRow(
                        row = row,
                        seen = seen,
                        onHighlight = { highlighted = it },
                        onOpen = viewModel::openCard,
                    )
                }
            }
        }
    }
}

/** The rows a chosen section keeps out of the home shelf: an empty shelf is not a shelf. */
private fun forCatalog(rows: List<HomeRows.Row>, catalog: Catalog): List<HomeRows.Row> {
    val keep = keeperFor(catalog) ?: return rows
    return rows.mapNotNull { row ->
        val items = row.items.filter(keep)
        if (items.isEmpty()) null else row.copy(items = items)
    }
}

/**
 * A whole section, as its own shelves: everything of that kind, under the
 * category the portal filed it in, in the order it arrived. Nothing is dropped.
 */
private fun sectionRows(cards: List<HomeRows.Card>, catalog: Catalog): List<HomeRows.Row> {
    val keep = keeperFor(catalog) ?: return emptyList()
    val byGroup = LinkedHashMap<String, MutableList<HomeRows.Card>>()
    for (card in cards) {
        if (keep(card)) byGroup.getOrPut(card.group) { mutableListOf() }.add(card)
    }
    return byGroup.map { (name, items) -> HomeRows.Row("group:$name", name, items) }
}

private fun keeperFor(catalog: Catalog): ((HomeRows.Card) -> Boolean)? = when (catalog) {
    Catalog.MOVIES -> { c -> c.kind != "LIVE" && c.contentType != "SERIES" }
    Catalog.SERIES -> { c -> c.contentType == "SERIES" }
    else -> null
}

/**
 * How much of each catalogue arrived, and what did not. A title someone expects
 * and cannot find is either missing from the portal or lost on the way in, and a
 * count is the difference between the two.
 */
private fun summary(cards: List<HomeRows.Card>, notes: List<String>): String? {
    if (cards.isEmpty()) return null
    // Channels are counted on the door; this is the library, so count the library.
    val live = cards.count { it.kind == "LIVE" }
    val series = cards.count { it.contentType == "SERIES" }
    val counts = "${cards.size - live - series} סרטים · $series סדרות"
    val trouble = notes.joinToString(" · ")
    return if (trouble.isBlank()) counts else "$counts · $trouble"
}

private fun describe(card: HomeRows.Card, resumeAt: Long): String {
    val what = when {
        card.kind == "LIVE" -> "שידור חי"
        card.contentType == "SERIES" -> "סדרה"
        else -> "סרט"
    }
    val resume = if (resumeAt > 0) " · המשך מ-" + clock(resumeAt) else ""
    return "$what · ${card.group}$resume · אישור להפעלה"
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
    seen: Set<String>,
    onHighlight: (HomeRows.Card) -> Unit,
    onOpen: (HomeRows.Card) -> Unit,
) {
    Column {
        SectionHeading("${row.title} · ${row.items.size}")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(tz(8))) {
            items(row.items, key = { it.id }) { card ->
                PosterCard(
                    name = card.name,
                    art = card.logo,
                    seen = card.id in seen,
                    progress = card.progress.toFloat(),
                    width = tz(124),
                    onFocus = { onHighlight(card) },
                    onClick = { onHighlight(card); onOpen(card) },
                )
            }
        }
    }
}
