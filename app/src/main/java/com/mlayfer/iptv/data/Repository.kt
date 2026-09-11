package com.mlayfer.iptv.data

/** Loads playlists and guides, with an in-memory cache for the app session. */
class Repository {

    private val playlistCache = HashMap<String, ParsedPlaylist>()
    private val epgCache = HashMap<String, Map<String, List<Programme>>>()
    private val episodeCache = HashMap<String, List<Channel>>()

    /** Blocking: call from a background dispatcher. */
    fun loadPlaylist(playlist: Playlist, force: Boolean = false, onStage: (String) -> Unit = {}): ParsedPlaylist {
        if (!force) playlistCache[playlist.id]?.let { return it }

        val parsed = when (val source = playlist.source) {
            is PlaylistSource.Text -> M3uParser.parse(source.content)
            is PlaylistSource.Url -> {
                val body = Http.fetchText(source.url)
                if (!body.contains("#EXTM3U") && !body.contains("#EXTINF")) {
                    throw Http.HttpException("הכתובת לא מחזירה קובץ M3U תקין")
                }
                M3uParser.parse(body)
            }
            is PlaylistSource.Xtream -> XtreamClient.load(source, onStage)
        }

        if (parsed.channels.isEmpty()) throw Http.HttpException("לא נמצאו ערוצים ברשימה")
        playlistCache[playlist.id] = parsed
        return parsed
    }

    /** Blocking: call from a background dispatcher. */
    fun loadEpg(url: String, force: Boolean = false): Map<String, List<Programme>> {
        if (!force) epgCache[url]?.let { return it }
        val xml = Http.fetchText(url)
        if (!xml.contains("<programme")) {
            throw Http.HttpException("הכתובת לא מחזירה מדריך שידורים בפורמט XMLTV")
        }
        val parsed = XmltvParser.parse(xml)
        epgCache[url] = parsed
        return parsed
    }

    /**
     * Blocking: call from a background dispatcher. Episodes are fetched per
     * series because a portal with thousands of series can't be expanded up front.
     */
    fun loadEpisodes(playlist: Playlist, seriesId: String, seriesName: String? = null): List<Channel> {
        val source = playlist.source
        if (source !is PlaylistSource.Xtream) return emptyList()

        val key = "${'$'}{playlist.id}#${'$'}seriesId"
        episodeCache[key]?.let { return it }

        val episodes = XtreamClient.loadEpisodes(source, seriesId, seriesName)
        episodeCache[key] = episodes
        return episodes
    }

    fun forget(playlistId: String) {
        playlistCache.remove(playlistId)
        episodeCache.keys.removeAll { it.startsWith("${'$'}playlistId#") }
    }
}
