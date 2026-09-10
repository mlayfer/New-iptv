package com.mlayfer.iptv.data

/**
 * What the home screen shows, decided in one place because both apps must
 * agree. A home screen is not a menu: it is the answer to "what do I watch
 * now", so it opens with what you were watching, then what you marked, then
 * what you watched last, and only then the catalogue.
 *
 * The Tizen app runs the same rules in tizen/core.js, and shared/parity/
 * fixtures check the two against each other.
 */
object HomeRows {

    private const val RESUME_MIN_SECONDS = 30L
    private const val RESUME_MAX_RATIO = 0.95
    private const val ROW_LIMIT = 20
    private const val RECENT_LIMIT = 12
    private const val FAVORITE_LIMIT = 20
    private const val CATEGORY_ROWS = 3
    private const val SEARCH_ROW_LIMIT = 40
    private const val SEARCH_MIN_QUERY = 2

    /** A card on the home screen: any item, live or on demand, in one shape. */
    data class Card(
        val id: String,
        val name: String,
        val group: String,
        /** "LIVE" or "VOD", matching the JavaScript model. */
        val kind: String,
        /** "LIVE", "MOVIE", "SERIES" or "EPISODE". */
        val contentType: String,
        val logo: String? = null,
        /** Another title the portal gave for the same thing, if it gave one. */
        val alias: String? = null,
        /** Seconds to resume from, 0 when the item was never started. */
        val resumeAt: Long = 0,
        val progress: Double = 0.0,
    )

    data class Entry(val id: String, val at: Long, val position: Long = 0, val duration: Long = 0)

    data class Row(val key: String, val title: String, val items: List<Card>)

    fun isResumable(entry: Entry): Boolean {
        if (entry.position <= 0) return false
        if (entry.position < RESUME_MIN_SECONDS) return false
        if (entry.duration > 0 && entry.position > entry.duration * RESUME_MAX_RATIO) return false
        return true
    }

    fun progressRatio(entry: Entry): Double {
        if (entry.duration <= 0 || entry.position <= 0) return 0.0
        return (entry.position.toDouble() / entry.duration).coerceIn(0.0, 1.0)
    }

    fun build(items: List<Card>, history: List<Entry>, favorites: List<String>): List<Row> {
        val byId = items.associateBy { it.id }
        val ordered = history.sortedByDescending { it.at }
        val favoriteSet = favorites.toSet()
        val rows = ArrayList<Row>()

        val resume = ordered.asSequence()
            .mapNotNull { entry -> byId[entry.id]?.let { entry to it } }
            .filter { (entry, card) -> card.kind != "LIVE" && isResumable(entry) }
            .take(RECENT_LIMIT)
            .map { (entry, card) ->
                card.copy(resumeAt = entry.position, progress = progressRatio(entry))
            }
            .toList()
        if (resume.isNotEmpty()) rows.add(Row("continue", "המשך לצפות", resume))

        val favs = favorites.mapNotNull { byId[it] }.take(FAVORITE_LIMIT)
        if (favs.isNotEmpty()) rows.add(Row("favorites", "המועדפים שלי", favs))

        val recentLive = ordered.asSequence()
            .mapNotNull { byId[it.id] }
            .filter { it.kind == "LIVE" && it.id !in favoriteSet }
            .take(RECENT_LIMIT)
            .toList()
        if (recentLive.isNotEmpty()) rows.add(Row("recentLive", "ערוצים שנצפו לאחרונה", recentLive))

        topGroups(items.filter { it.kind == "LIVE" }).forEach {
            rows.add(Row("live:${it.first}", it.first, it.second))
        }
        topGroups(items.filter { it.kind != "LIVE" && it.contentType != "SERIES" }).forEach {
            rows.add(Row("movie:${it.first}", it.first, it.second))
        }
        val series = items.filter { it.contentType == "SERIES" }
        if (series.isNotEmpty()) rows.add(Row("series", "סדרות", series.take(ROW_LIMIT)))

        return rows.filter { it.items.isNotEmpty() }
    }

    /**
     * One box over the whole catalogue. A title someone remembers is not filed
     * under the section they happen to be standing in, so the search never asks
     * which one that is; results come back grouped by what they are.
     */
    fun search(items: List<Card>, query: String, limit: Int = SEARCH_ROW_LIMIT): List<Row> {
        val q = query.trim().lowercase()
        if (q.length < SEARCH_MIN_QUERY) return emptyList()

        val live = ArrayList<Card>()
        val movies = ArrayList<Card>()
        val series = ArrayList<Card>()
        val wanted = Search.skeleton(q)
        for (item in items) {
            val text = Search.searchableText(item.name, item.group, item.alias)
            if (!Search.matches(text, q, wanted)) continue
            when {
                item.kind == "LIVE" -> if (live.size < limit) live.add(item)
                item.contentType == "SERIES" -> if (series.size < limit) series.add(item)
                else -> if (movies.size < limit) movies.add(item)
            }
        }

        val rows = ArrayList<Row>()
        if (series.isNotEmpty()) rows.add(Row("series", "סדרות", series))
        if (movies.isNotEmpty()) rows.add(Row("movies", "סרטים", movies))
        if (live.isNotEmpty()) rows.add(Row("live", "ערוצים", live))
        return rows
    }

    /** Newest first, one entry per item, capped — the same list both apps store. */
    fun mergeHistory(history: List<Entry>, entry: Entry, limit: Int = 60): List<Entry> {
        val out = ArrayList<Entry>(limit)
        out.add(entry)
        for (old in history) {
            if (old.id == entry.id) continue
            if (out.size >= limit) break
            out.add(old)
        }
        return out
    }

    /**
     * Biggest category first; ties keep the order the portal returned them in,
     * so the home screen does not reshuffle itself between loads.
     */
    private fun topGroups(pool: List<Card>): List<Pair<String, List<Card>>> {
        val byGroup = LinkedHashMap<String, MutableList<Card>>()
        for (item in pool) {
            byGroup.getOrPut(item.group.ifBlank { "ללא קטגוריה" }) { ArrayList() }.add(item)
        }
        val order = byGroup.keys.toList()
        val rank = order.withIndex().associate { (i, g) -> g to i }
        return order
            .sortedWith(compareByDescending<String> { byGroup.getValue(it).size }
                .thenBy { rank.getValue(it) })
            .take(CATEGORY_ROWS)
            .map { it to byGroup.getValue(it).take(ROW_LIMIT) }
    }
}
