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

    private val TIMESHIFT_PHP = Regex("^(.*?)/streaming/timeshift\\.php\\?(.*)$")
    private val TIMESHIFT_PATH =
        Regex("^(.*?)/timeshift/([^/]+)/([^/]+)/(\\d+)/([^/]+)/(\\d+)(\\.\\w+)?$")

    /**
     * The two shapes a panel serves its archive at.
     *
     * One is a script with a query, the other a path; a panel enables one of
     * them and answers the other with an error page, so the one the address was
     * built in is a guess until it plays.
     */
    fun timeshiftAlternatives(url: String): List<String> {
        TIMESHIFT_PHP.find(url)?.let { m ->
            val query = m.groupValues[2].split("&").mapNotNull { pair ->
                val at = pair.indexOf('=')
                if (at > 0) pair.substring(0, at) to pair.substring(at + 1) else null
            }.toMap()

            val id = query["stream"].orEmpty()
            val start = query["start"].orEmpty()
            val duration = query["duration"].orEmpty()
            if (id.isEmpty() || start.isEmpty() || duration.isEmpty()) return emptyList()

            val stamp = java.net.URLDecoder.decode(start, "UTF-8")
            val stem = m.groupValues[1] + "/timeshift/" + query["username"].orEmpty() +
                "/" + query["password"].orEmpty() + "/" + duration + "/" + stamp + "/" + id
            return listOf("$stem.m3u8", "$stem.ts")
        }

        TIMESHIFT_PATH.find(url)?.let { m ->
            val prefix = m.groupValues[1]
            val user = m.groupValues[2]
            val pass = m.groupValues[3]
            val duration = m.groupValues[4]
            val stamp = m.groupValues[5]
            val id = m.groupValues[6]
            val stem = "$prefix/timeshift/$user/$pass/$duration/$stamp/$id"
            val other = if (m.groupValues[7] == ".ts") "$stem.m3u8" else "$stem.ts"
            return listOf(
                other,
                "$prefix/streaming/timeshift.php?username=$user&password=$pass" +
                    "&stream=$id&start=" + java.net.URLEncoder.encode(stamp, "UTF-8") +
                    "&duration=$duration",
            )
        }
        return emptyList()
    }

    fun of(url: String): List<String> {
        // An archive address is not a stream id with an extension on it, so the
        // rewriting below would leave it alone; its alternatives are their own
        // shape.
        val shifted = timeshiftAlternatives(url)
        if (shifted.isNotEmpty()) return (listOf(url) + shifted).take(MAX_VARIANTS)

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
