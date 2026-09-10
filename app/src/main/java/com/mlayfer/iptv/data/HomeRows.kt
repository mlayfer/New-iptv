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
    private const val TASTE_HALF_LIFE_MS = 21L * 24 * 60 * 60 * 1000
    private const val TASTE_FLOOR = 0.05
    private const val TASTE_BASE = 0.3
    private const val BECAUSE_MIN = 3

    /** The bucket an item with no category of its own falls into. */
    private const val NO_GROUP = "ללא קטגוריה"

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

    /**
     * What counts as seen: something marked by hand, or something played to the
     * end. Both apps ask this the same way, because a tick on one screen has to
     * mean a tick on the other.
     */
    fun watchedSet(history: List<Entry>, marks: List<String>): Set<String> {
        val seen = HashSet<String>(marks.filter { it.isNotBlank() })
        for (entry in history) {
            if (entry.duration <= 0 || entry.position <= 0) continue
            if (entry.position > entry.duration * RESUME_MAX_RATIO) seen.add(entry.id)
        }
        return seen
    }

    data class Taste(val groups: Map<String, Double>, val order: List<String>)

    private fun decayAt(at: Long, now: Long): Double {
        val age = now - at
        if (age <= 0) return 1.0
        return Math.pow(0.5, age.toDouble() / TASTE_HALF_LIFE_MS).coerceAtLeast(TASTE_FLOOR)
    }

    /**
     * A taste is a weighted count of the categories actually spent time in. Two
     * things move the weight: how recently (a month-old evening says less about
     * tonight) and how much of it got watched — five minutes is not a vote.
     */
    fun taste(
        items: List<Card>,
        history: List<Entry>,
        marks: List<String>,
        now: Long,
    ): Taste {
        val byId = items.associateBy { it.id }
        val seen = watchedSet(history, marks)
        val groups = LinkedHashMap<String, Double>()

        fun add(id: String, at: Long, ratio: Double) {
            // Channels are a different appetite from a film; they do not vote.
            val card = byId[id] ?: return
            if (card.kind == "LIVE") return
            val group = card.group.ifBlank { NO_GROUP }
            val weight = decayAt(at, now) * (TASTE_BASE + (1 - TASTE_BASE) * ratio)
            groups[group] = (groups[group] ?: 0.0) + weight
        }

        for (entry in history) {
            add(entry.id, entry.at, if (entry.id in seen) 1.0 else progressRatio(entry))
        }
        val inHistory = history.map { it.id }.toSet()
        for (id in marks) if (id !in inHistory) add(id, now, 1.0)

        val order = groups.keys.sortedWith(
            compareByDescending<String> { groups[it] ?: 0.0 }.thenBy { it }
        )
        return Taste(groups, order)
    }

    /**
     * More of what you like, minus what you have already seen. Scored by taste,
     * then capped per category — twenty titles from one shelf is a shelf, not a
     * recommendation.
     */
    fun recommend(
        items: List<Card>,
        history: List<Entry>,
        marks: List<String>,
        now: Long,
        limit: Int = ROW_LIMIT,
    ): List<Card> {
        val profile = taste(items, history, marks, now)
        if (profile.order.isEmpty()) return emptyList()

        val seen = watchedSet(history, marks)
        // Whatever is waiting in "continue watching" has its own row already.
        val resuming = history.filter { isResumable(it) }.map { it.id }.toSet()

        val scored = items.asSequence()
            .withIndex()
            .filter { (_, card) ->
                card.kind != "LIVE" && card.id !in seen && card.id !in resuming
            }
            .mapNotNull { (index, card) ->
                val score = profile.groups[card.group.ifBlank { NO_GROUP }] ?: 0.0
                if (score > 0) Triple(card, score, index) else null
            }
            .sortedWith(compareByDescending<Triple<Card, Double, Int>> { it.second }.thenBy { it.third })

        val perGroup = maxOf(2, limit / 3)
        val taken = HashMap<String, Int>()
        val out = ArrayList<Card>()
        for ((card, _, _) in scored) {
            if (out.size >= limit) break
            val group = card.group.ifBlank { NO_GROUP }
            if ((taken[group] ?: 0) >= perGroup) continue
            taken[group] = (taken[group] ?: 0) + 1
            out.add(card)
        }
        return out
    }

    data class Because(val seed: Card, val items: List<Card>)

    /**
     * The most recent thing finished, and what sits next to it. Named after the
     * title so the row explains itself.
     */
    fun becauseYouWatched(
        items: List<Card>,
        history: List<Entry>,
        marks: List<String>,
        limit: Int = ROW_LIMIT,
    ): Because? {
        val byId = items.associateBy { it.id }
        val seen = watchedSet(history, marks)

        var seed = history.sortedByDescending { it.at }
            .asSequence()
            .mapNotNull { byId[it.id] }
            .firstOrNull { it.kind != "LIVE" && it.id in seen }
        // Nothing finished yet: fall back to what was marked by hand, newest last.
        if (seed == null) {
            seed = marks.asReversed().asSequence()
                .mapNotNull { byId[it] }
                .firstOrNull { it.kind != "LIVE" }
        }
        val chosen = seed ?: return null

        val group = chosen.group.ifBlank { NO_GROUP }
        val near = items.asSequence()
            .filter { it.id != chosen.id && it.kind != "LIVE" && it.id !in seen }
            .filter { it.group.ifBlank { NO_GROUP } == group }
            .take(limit)
            .toList()
        if (near.size < BECAUSE_MIN) return null
        return Because(chosen, near)
    }

    fun build(
        items: List<Card>,
        history: List<Entry>,
        favorites: List<String>,
        marks: List<String> = emptyList(),
        now: Long = 0,
    ): List<Row> {
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

        // What to watch next, before the catalogue starts talking about itself.
        // The named row explains itself, so it wins any title the two both want:
        // two rows of the same films under different headings is one row too many.
        val because = becauseYouWatched(items, history, marks)
        val claimed = because?.items?.map { it.id }?.toSet() ?: emptySet()

        val suggested = recommend(items, history, marks, now).filter { it.id !in claimed }
        if (suggested.isNotEmpty()) rows.add(Row("recommended", "מומלץ בשבילך", suggested))

        because?.let {
            rows.add(Row("because:${it.seed.id}", "כי צפית ב־${it.seed.name}", it.items))
        }

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
            byGroup.getOrPut(item.group.ifBlank { NO_GROUP }) { ArrayList() }.add(item)
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
