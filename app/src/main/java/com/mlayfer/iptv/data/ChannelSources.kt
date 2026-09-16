package com.mlayfer.iptv.data

/**
 * A channel and its backups are one channel, not three.
 *
 * Providers list a backup feed as a channel of its own, flat, right after the
 * one it backs up: "קשת 12", then "קשת 12 גיבוי", then "קשת 12 גיבוי 2". On a
 * television that is three tiles that look the same, and only pressing them
 * tells you which one is alive. Here a backup stops being a channel and becomes
 * what it actually is — another way to reach the same channel. The list shows
 * the channel once; the player falls through to the next source when one dies.
 *
 * The rule is deliberately narrow, because folding the wrong thing hides a
 * channel, which is worse than the clutter it fixes. A backup has to SAY it is
 * one — by name ("גיבוי", "backup"), by its category, or by being the same name
 * with an index after it. "ספורט 1" and "ספורט 2" are two channels and stay
 * two; "קשת 12" and "קשת 12 2" are one, because the name they share is itself a
 * channel on the list.
 *
 * Nothing is ever dropped: a backup whose channel is not on the list stays a
 * channel in its own right.
 */
object ChannelSources {

    /** Beyond this the ladder is longer than anyone's patience. */
    const val MAX_SOURCES = 6

    private val BACKUP_WORDS = setOf(
        "גיבוי", "גיבויים", "רזרבה", "רזרבי", "חלופי", "חלופה", "משני",
        "backup", "bkup", "bck", "bk", "alt", "alternate", "alternative",
        "mirror", "reserve", "spare", "second", "secondary",
    )

    /** Says how the picture looks, never which channel it is. */
    private val QUALITY_WORDS = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "h265", "h264", "hevc", "avc",
        "1080p", "720p", "576p", "480p", "1080i", "50fps", "60fps", "fps", "raw",
    )

    private val CATEGORY_MARKS = listOf("גיבוי", "רזרב", "backup", "back up", "mirror")

    /**
     * An index with no word on it ("קשת 12 2") only counts as a backup when it
     * is small — a channel called "ערוץ 24" is not the 24th backup of "ערוץ".
     */
    private const val WEAK_MAX = 9
    private const val INDEX_MAX = 20

    private val SPLIT = Regex("[\\s()\\[\\]{}|/\\\\]+")
    private val EDGE = "-–—_,.:;־״׳\"'`".toCharArray()

    private fun words(name: String): List<String> =
        SPLIT.split(name).map { it.trim(*EDGE) }.filter { it.isNotEmpty() }

    /** What is left of a name once the wrapping is off: the channel itself. */
    private data class Parsed(
        val base: String,
        val full: String,
        /** Null when the name says nothing about being a backup. */
        val index: Int?,
        val explicit: Boolean,
    )

    private fun parse(name: String): Parsed {
        var list = words(name)
        val full = list.filterNot { QUALITY_WORDS.contains(it.lowercase()) }
            .joinToString(" ") { it.lowercase() }

        var explicit = false
        var index: Int? = null

        // "גיבוי קשת 12" — the word can lead as easily as it can trail.
        if (list.isNotEmpty() && BACKUP_WORDS.contains(list.first().lowercase())) {
            explicit = true
            list = list.drop(1)
        }

        var scanning = true
        while (scanning && list.isNotEmpty()) {
            val last = list.last().lowercase()
            val number = last.toIntOrNull()
            // The word before the number decides what the number is. "גיבוי 1"
            // is the first backup; "ספורט 1" is a channel. Reading the number
            // on its own is what made every "X - (גיבוי 1)" on a real portal
            // fall straight past the rule meant to catch it: the 1 was refused
            // for looking like a channel number, and the word behind it was
            // never reached.
            val after = list.getOrNull(list.size - 2)?.lowercase()
            when {
                QUALITY_WORDS.contains(last) -> list = list.dropLast(1)
                number != null && index == null && number in 1..INDEX_MAX &&
                    after != null && BACKUP_WORDS.contains(after) -> {
                    index = number
                    list = list.dropLast(1)
                }
                // A bare index, with no word to say what it counts. Narrower on
                // purpose, and it still has to survive [weakHolds].
                number != null && index == null && number in 2..INDEX_MAX -> {
                    index = number
                    list = list.dropLast(1)
                }
                BACKUP_WORDS.contains(last) -> {
                    explicit = true
                    if (index == null) index = 1
                    list = list.dropLast(1)
                }
                else -> scanning = false
            }
        }

        val base = list.filterNot { QUALITY_WORDS.contains(it.lowercase()) }
            .joinToString(" ") { it.lowercase() }
        return Parsed(base = base, full = full, index = index, explicit = explicit)
    }

    private fun categorySaysBackup(group: String?): Boolean {
        val g = group?.lowercase() ?: return false
        return CATEGORY_MARKS.any { g.contains(it) }
    }

    /** A bare index only folds when the name it would fold into ends in a number. */
    private fun weakHolds(base: String): Boolean {
        val last = base.split(' ').lastOrNull() ?: return false
        return last.toIntOrNull() != null
    }

    /**
     * The same list, with every backup moved inside the channel it backs up.
     * Order is untouched, and a channel with no backups comes back as it was.
     */
    fun fold(channels: List<Channel>): List<Channel> {
        val live = channels.count { it.kind == ChannelKind.LIVE }
        if (live < 2) return channels

        // Keyed by position, never by id: a portal is free to hand out the same
        // id twice, and two channels sharing one would erase each other here.
        val parsed = arrayOfNulls<Parsed>(channels.size)
        val primaryOf = HashMap<String, Int>()

        channels.forEachIndexed { i, channel ->
            if (channel.kind != ChannelKind.LIVE || channel.url.isBlank()) return@forEachIndexed
            val p = parse(channel.name)
            parsed[i] = p
            val marked = p.explicit || categorySaysBackup(channel.group)
            // A backup never stands in as the channel others fold into.
            if (!marked && !primaryOf.containsKey(p.full)) primaryOf[p.full] = i
        }

        // Which channel each backup belongs to, by position in the list.
        val attach = HashMap<Int, Int>()
        channels.forEachIndexed { i, channel ->
            val p = parsed[i] ?: return@forEachIndexed
            val marked = p.explicit || categorySaysBackup(channel.group)
            val key = when {
                marked -> if (p.base.isNotEmpty()) p.base else p.full
                p.index != null && p.index <= WEAK_MAX && weakHolds(p.base) -> p.base
                else -> return@forEachIndexed
            }
            val target = primaryOf[key] ?: return@forEachIndexed
            if (target != i) attach[i] = target
        }

        if (attach.isEmpty()) return channels

        // A backup of a backup belongs to the channel at the root of the chain.
        fun root(start: Int): Int {
            var at = start
            var guard = 0
            while (attach.containsKey(at) && guard++ < MAX_SOURCES) at = attach[at]!!
            return at
        }

        val extras = HashMap<Int, MutableList<Channel>>()
        for ((from, _) in attach.entries.sortedBy { it.key }) {
            extras.getOrPut(root(from)) { mutableListOf() }.add(channels[from])
        }

        val out = ArrayList<Channel>(channels.size)
        channels.forEachIndexed { i, channel ->
            if (attach.containsKey(i)) return@forEachIndexed
            val more = extras[i]
            out.add(
                if (more.isNullOrEmpty()) channel
                else channel.copy(alternates = more.take(MAX_SOURCES - 1))
            )
        }
        return out
    }

    /** Every way to reach this channel, the channel itself first. */
    fun sourcesOf(channel: Channel): List<Channel> =
        (listOf(channel.copy(alternates = emptyList())) + channel.alternates).take(MAX_SOURCES)
}
