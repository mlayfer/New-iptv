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

data class ParsedPlaylist(val channels: List<Channel>, val epgUrl: String? = null)

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String? = null)

data class RecentEntry(val channelId: String, val playlistId: String, val at: Long)
