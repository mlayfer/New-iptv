package com.mlayfer.iptv.data

/**
 * The same Xtream channel is reachable at several endpoints, and panels enable
 * only some of them: `/live/USER/PASS/ID.m3u8` (HLS), `.../ID.ts` (raw MPEG-TS)
 * and `.../ID` with no extension. A playlist hands out exactly one of them, so a
 * disabled HLS output looks like a dead channel even though the stream is up —
 * the panel answers with an HTML error page and HTTP 200.
 *
 * These are alternatives to try, not a guess about which one works.
 */
object StreamVariants {

    private const val MAX_VARIANTS = 4

    fun of(url: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(url)

        val cut = url.indexOfFirst { it == '?' || it == '#' }
        val base = if (cut == -1) url else url.substring(0, cut)
        val query = if (cut == -1) "" else url.substring(cut)

        val slash = base.lastIndexOf('/')
        if (slash <= 0) return out.toList()

        val prefix = base.substring(0, slash)
        val last = base.substring(slash + 1)
        val id = last.substringBefore('.')

        // Only stream ids get rewritten; a path like /live/index.m3u8 is a real
        // playlist name, not an Xtream stream id.
        if (id.isEmpty() || !id.all { it.isDigit() }) return out.toList()

        for (candidate in listOf("$id.ts", id, "$id.m3u8")) {
            out.add("$prefix/$candidate$query")
        }

        // Older panels serve the transport stream without the /live/ segment.
        if (prefix.contains("/live/")) {
            val legacy = prefix.replaceFirst("/live/", "/")
            out.add("$legacy/$id$query")
        }

        return out.toList().take(MAX_VARIANTS)
    }
}
