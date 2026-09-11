package com.mlayfer.iptv.data

import java.util.Calendar
import java.util.TimeZone

/**
 * Minimal XMLTV reader. A full guide dump is far larger than the UI needs, so
 * only programmes overlapping the requested window are kept.
 */
object XmltvParser {

    private val programmeRegex =
        Regex("<programme\\b([^>]*)>([\\s\\S]*?)</programme>", RegexOption.IGNORE_CASE)
    private val attrRegex = Regex("([\\w-]+)\\s*=\\s*\"([^\"]*)\"")
    private val titleRegex = Regex("<title\\b[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
    private val descRegex = Regex("<desc\\b[^>]*>([\\s\\S]*?)</desc>", RegexOption.IGNORE_CASE)
    private val cdataRegex = Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>")
    private val hexEntityRegex = Regex("&#x([0-9a-fA-F]+);")
    private val decEntityRegex = Regex("&#(\\d+);")
    private val namedEntityRegex = Regex("&(\\w+);")
    private val timeRegex =
        Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})?\\s*([+-]\\d{4})?")

    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    )

    fun parse(
        xml: String,
        now: Long = System.currentTimeMillis(),
        windowBefore: Long = 6 * 3_600_000L,
        windowAfter: Long = 36 * 3_600_000L,
        maxPerChannel: Int = 24,
    ): Map<String, List<Programme>> {
        val from = now - windowBefore
        val to = now + windowAfter
        val map = HashMap<String, MutableList<Programme>>()

        for (match in programmeRegex.findAll(xml)) {
            val attrs = attrRegex.findAll(match.groupValues[1])
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val channel = attrs["channel"] ?: continue

            val start = parseTime(attrs["start"] ?: "") ?: continue
            val stop = attrs["stop"]?.let { parseTime(it) } ?: (start + 3_600_000L)
            if (stop < from || start > to) continue

            val body = match.groupValues[2]
            val title = decode(titleRegex.find(body)?.groupValues?.get(1) ?: "")
            if (title.isEmpty()) continue
            val desc = decode(descRegex.find(body)?.groupValues?.get(1) ?: "").ifEmpty { null }

            val list = map.getOrPut(channel) { ArrayList() }
            if (list.size < maxPerChannel) list.add(Programme(start, stop, title, desc))
        }

        return map.mapValues { (_, list) -> list.sortedBy { it.start } }
    }

    /** XMLTV timestamps look like `20260909183000 +0300`; the offset is optional. */
    fun parseTime(value: String): Long? {
        val m = timeRegex.find(value.trim()) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        val s = m.groupValues[6].ifEmpty { "0" }
        val tz = m.groupValues[7]

        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        val base = calendar.timeInMillis
        if (tz.isEmpty()) return base

        val sign = if (tz[0] == '-') -1 else 1
        val offset = (tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt()) * 60_000L
        return base - sign * offset
    }

    private fun decode(value: String): String {
        var out = cdataRegex.replace(value) { it.groupValues[1] }
        out = hexEntityRegex.replace(out) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        out = decEntityRegex.replace(out) {
            it.groupValues[1].toInt().toChar().toString()
        }
        out = namedEntityRegex.replace(out) { match ->
            entities[match.groupValues[1].lowercase()] ?: match.value
        }
        return out.trim()
    }

    /**
     * What is on at `at`. A guide with overlapping entries is a guide with a
     * mistake in it; the one that started last is the one on air. The JS twin
     * in core.js answers the same way, and the parity fixtures hold them to it.
     */
    fun programmeAt(list: List<Programme>?, at: Long): Programme? =
        list?.filter { at >= it.start && at < it.stop }?.maxByOrNull { it.start }

    fun nextProgramme(list: List<Programme>?, at: Long): Programme? =
        list?.filter { it.start > at }?.minByOrNull { it.start }

    /**
     * The last few things that were on, oldest first.
     *
     * Only useful where the portal kept them: a guide that lists what has
     * already finished and cannot be played back is a list of regrets.
     */
    fun alreadyOn(list: List<Programme>?, at: Long, limit: Int): List<Programme> =
        list.orEmpty()
            .filter { it.stop <= at }
            .sortedBy { it.start }
            .takeLast(limit)

    /** The next few things on, in the order they will be on. */
    fun upcoming(list: List<Programme>?, at: Long, limit: Int? = null): List<Programme> {
        val ahead = list.orEmpty().filter { it.start > at }.sortedBy { it.start }
        return if (limit == null) ahead else ahead.take(limit)
    }
}
