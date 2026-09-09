package com.mlayfer.iptv.data

/** Loads playlists and guides, with an in-memory cache for the app session. */
class Repository {

    private val playlistCache = HashMap<String, ParsedPlaylist>()
    private val epgCache = HashMap<String, Map<String, List<Programme>>>()

    /** Blocking: call from a background dispatcher. */
    fun loadPlaylist(playlist: Playlist, force: Boolean = false): ParsedPlaylist {
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
            is PlaylistSource.Xtream -> XtreamClient.load(source)
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

    fun forget(playlistId: String) {
        playlistCache.remove(playlistId)
    }
}
