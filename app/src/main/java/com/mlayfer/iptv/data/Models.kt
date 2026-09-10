package com.mlayfer.iptv.data

enum class ChannelKind { LIVE, VOD }

data class Channel(
    /** Stable key derived from the stream URL, so favorites survive a refresh. */
    val id: String,
    val name: String,
    val url: String,
    val kind: ChannelKind,
    val group: String? = null,
    val logo: String? = null,
    /**
     * Another title for the same thing — the original name when the portal
     * sends one, so a show listed in Hebrew is still findable in English.
     */
    val alias: String? = null,
    /** XMLTV id, used to join the channel with its guide entries. */
    val tvgId: String? = null,
    /** Playback hints from the playlist. Unlike a browser, the app can honour these. */
    val userAgent: String? = null,
    val referrer: String? = null,
)

sealed class PlaylistSource {
    data class Url(val url: String) : PlaylistSource()
    data class Text(val content: String) : PlaylistSource()
    data class Xtream(
        val server: String,
        val username: String,
        val password: String,
        val includeVod: Boolean,
    ) : PlaylistSource()
}

data class Playlist(
    val id: String,
    val name: String,
    val source: PlaylistSource,
    val epgUrl: String? = null,
    val createdAt: Long = 0L,
) {
    val label: String
        get() = when {
            name.isNotBlank() -> name
            source is PlaylistSource.Url -> source.url
            source is PlaylistSource.Xtream -> source.server
            else -> "רשימה מקומית"
        }
}

/**
 * A series is not playable by itself: its episodes live behind another request,
 * and a portal with thousands of series can't be expanded up front.
 */
data class Series(
    val id: String,
    val name: String,
    val logo: String? = null,
    val group: String? = null,
    val alias: String? = null,
)

data class ParsedPlaylist(
    val channels: List<Channel>,
    val epgUrl: String? = null,
    val series: List<Series> = emptyList(),
    /** What the portal refused or cut short, so the UI can say so out loud. */
    val notes: List<String> = emptyList(),
)

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String? = null)

/**
 * Something watched, and how far in. The position is what makes "continue
 * watching" possible; a live channel simply leaves it at zero.
 */
data class RecentEntry(
    val channelId: String,
    val playlistId: String,
    val at: Long,
    val position: Long = 0,
    val duration: Long = 0,
    /** A copy of the card, so an episode survives a reload of the catalogue. */
    val name: String = "",
    val group: String = "",
    val kind: String = "",
    val contentType: String = "",
    val logo: String? = null,
)
