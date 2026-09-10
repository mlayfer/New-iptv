package com.mlayfer.iptv.data

/** Pure list filtering, kept out of the UI so it can be unit tested. */
object Filtering {

    private val noiseRegex = Regex("[\"'`״׳־\\-]")

    fun normalize(value: String): String = noiseRegex.replace(value.lowercase(), "")

    fun apply(
        channels: List<Channel>,
        query: String = "",
        group: String? = null,
        kind: ChannelKind? = null,
        favoritesOnly: Boolean = false,
        favorites: Set<String> = emptySet(),
        /** Channel ids in "most recent first" order; null unless showing history. */
        recentOrder: List<String>? = null,
    ): List<Channel> {
        var list = channels

        if (kind != null) list = list.filter { it.kind == kind }
        if (favoritesOnly) list = list.filter { favorites.contains(it.id) }

        if (recentOrder != null) {
            val rank = recentOrder.withIndex().associate { (index, id) -> id to index }
            list = list.filter { rank.containsKey(it.id) }.sortedBy { rank[it.id] ?: 0 }
        }

        if (group != null) {
            list = list.filter {
                (it.group?.trim().takeUnless { g -> g.isNullOrEmpty() } ?: M3uParser.NO_GROUP) == group
            }
        }

        val needle = normalize(query.trim())
        if (needle.isNotEmpty()) {
            // The same matcher the search screen uses, so a name typed in Latin
            // letters finds a channel the portal spells in Hebrew.
            val wanted = Search.skeleton(needle)
            list = list.filter {
                val text = Search.searchableText(
                    normalize(it.name),
                    normalize(it.group ?: ""),
                    it.alias?.let(::normalize),
                )
                Search.matches(text, needle, wanted)
            }
        }

        return list
    }
}
