package com.mlayfer.iptv.data

/**
 * Extended M3U parser. Tolerates what real providers actually ship: CRLF line
 * endings, #EXTGRP group lines, #EXTVLCOPT/#KODIPROP hints and entries whose
 * only name is the text after the comma.
 */
object M3uParser {

    private val attrRegex = Regex("([\\w-]+)\\s*=\\s*\"([^\"]*)\"")
    private val urlRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")
    private val vodExtRegex = Regex("\\.(mp4|mkv|avi|mov|m4v|flv|webm)($|\\?)", RegexOption.IGNORE_CASE)
    private val vodPathRegex = Regex("/(movie|series)/", RegexOption.IGNORE_CASE)
    private val vodGroupRegex =
        Regex("(vod|movies?|series|סרט|סרטים|סדרות)", RegexOption.IGNORE_CASE)

    fun parse(text: String): ParsedPlaylist {
        val channels = ArrayList<Channel>()
        val seen = HashSet<String>()
        var epgUrl: String? = null

        var pendingName: String? = null
        var pendingGroup: String? = null
        var pendingLogo: String? = null
        var pendingTvgId: String? = null
        var pendingUserAgent: String? = null
        var pendingReferrer: String? = null
        var extGrp: String? = null

        fun resetPending() {
            pendingName = null
            pendingGroup = null
            pendingLogo = null
            pendingTvgId = null
            pendingUserAgent = null
            pendingReferrer = null
            extGrp = null
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U") -> {
                    val attrs = readAttrs(line)
                    epgUrl = attrs["x-tvg-url"] ?: attrs["url-tvg"] ?: epgUrl
                }

                line.startsWith("#EXTINF") -> {
                    val (attrPart, displayName) = splitExtinf(line)
                    val attrs = readAttrs(attrPart)
                    resetPending()
                    pendingName = displayName.ifBlank { attrs["tvg-name"] ?: "ללא שם" }
                    pendingTvgId = attrs["tvg-id"]?.ifBlank { null }
                    pendingLogo = attrs["tvg-logo"]?.ifBlank { null }
                    pendingGroup = attrs["group-title"]?.ifBlank { null }
                }

                line.startsWith("#EXTGRP") -> {
                    extGrp = line.substringAfter(':', "").trim().ifBlank { null }
                }

                line.startsWith("#EXTVLCOPT") || line.startsWith("#KODIPROP") -> {
                    val value = line.substringAfter(':', "")
                    val key = value.substringBefore('=', "").trim().lowercase()
                    val setting = value.substringAfter('=', "").trim()
                    if (setting.isNotEmpty()) {
                        if (key.endsWith("http-user-agent") || key.endsWith("user-agent")) {
                            pendingUserAgent = setting
                        }
                        if (key.endsWith("http-referrer") || key.endsWith("referer")) {
                            pendingReferrer = setting
                        }
                    }
                }

                line.startsWith("#") -> Unit

                urlRegex.containsMatchIn(line) -> {
                    val name = pendingName ?: line
                    val group = pendingGroup ?: extGrp
                    val id = channelId(line, name)
                    if (seen.add(id)) {
                        channels.add(
                            Channel(
                                id = id,
                                name = name,
                                url = line,
                                kind = guessKind(line, group),
                                group = group,
                                logo = pendingLogo,
                                tvgId = pendingTvgId,
                                userAgent = pendingUserAgent,
                                referrer = pendingReferrer,
                            )
                        )
                    }
                    resetPending()
                }
            }
        }

        return ParsedPlaylist(channels, epgUrl)
    }

    /** The display name follows the first comma that is not inside a quoted value. */
    private fun splitExtinf(line: String): Pair<String, String> {
        val rest = line.substringAfter(':', "")
        var quoted = false
        for (i in rest.indices) {
            val c = rest[i]
            if (c == '"') quoted = !quoted
            else if (c == ',' && !quoted) return rest.substring(0, i) to rest.substring(i + 1).trim()
        }
        return rest to ""
    }

    private fun readAttrs(source: String): Map<String, String> =
        attrRegex.findAll(source).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun guessKind(url: String, group: String?): ChannelKind = when {
        vodExtRegex.containsMatchIn(url) -> ChannelKind.VOD
        vodPathRegex.containsMatchIn(url) -> ChannelKind.VOD
        group != null && vodGroupRegex.containsMatchIn(group) -> ChannelKind.VOD
        else -> ChannelKind.LIVE
    }

    fun channelId(url: String, name: String): String =
        hash(url) + hash(name).take(4)

    /** FNV-1a: short, stable ids without a hashing dependency. */
    private fun hash(value: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in value) {
            h = h xor c.code
            h *= 0x01000193
        }
        return (h.toLong() and 0xFFFFFFFFL).toString(36)
    }

    fun groups(channels: List<Channel>): List<Pair<String, Int>> =
        channels.groupingBy { it.group?.trim().takeUnless { g -> g.isNullOrEmpty() } ?: NO_GROUP }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    const val NO_GROUP = "ללא קטגוריה"
}
