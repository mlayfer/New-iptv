package com.mlayfer.iptv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Talks to an Xtream Codes portal and normalizes it into the same channel model. */
object XtreamClient {

    /**
     * Panels differ on where they put the original title, and most send none.
     * Whichever of these turns up is kept beside the name, so a show listed as
     * "ניתוק" is still found by typing "Severance".
     */
    private val ALIAS_FIELDS = listOf(
        "o_name", "original_name", "orig_name", "name_en", "english_name", "title",
    )

    private fun aliasOf(item: JSONObject, name: String): String? {
        for (field in ALIAS_FIELDS) {
            val value = item.optString(field).trim()
            if (value.isNotEmpty() && !value.equals(name, ignoreCase = true)) return value
        }
        return null
    }

    private const val MAX_PER_CATEGORY = 25_000
    private const val MAX_SERIES = 10_000

    fun load(source: PlaylistSource.Xtream): ParsedPlaylist {
        val server = normalizeServer(source.server)
        val user = source.username
        val password = source.password

        val account = JSONObject(Http.fetchText(api(server, user, password, null)))
        val info = account.optJSONObject("user_info")
        if (info != null) {
            if (info.optInt("auth", 1) == 0) throw Http.HttpException("שם המשתמש או הסיסמה שגויים")
            val status = info.optString("status")
            if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) {
                throw Http.HttpException("המנוי אינו פעיל ($status)")
            }
        }

        val creds = credentials(user, password)
        val channels = ArrayList<Channel>()
        val series = ArrayList<Series>()
        val notes = ArrayList<String>()

        val liveCategories = categoryNames(server, user, password, "get_live_categories")
        val liveStreams = readArray(api(server, user, password, "get_live_streams"))
        for (i in 0 until liveStreams.length()) {
            if (channels.size >= MAX_PER_CATEGORY) {
                notes.add("נטענו $MAX_PER_CATEGORY ערוצים חיים מתוך ${liveStreams.length()}")
                break
            }
            val item = liveStreams.optJSONObject(i) ?: continue
            val streamId = item.opt("stream_id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
            val url = "$server/live/$creds/$streamId.m3u8"
            val name = item.optString("name").trim().ifEmpty { "ערוץ $streamId" }
            channels.add(
                Channel(
                    id = M3uParser.channelId(url, name),
                    name = name,
                    url = url,
                    kind = ChannelKind.LIVE,
                    group = liveCategories[item.opt("category_id")?.toString()],
                    logo = item.optString("stream_icon").ifBlank { null },
                    tvgId = item.optString("epg_channel_id").ifBlank { null },
                )
            )
        }

        if (source.includeVod) {
            // A portal that refuses one catalogue must not look like a portal that
            // has none: say what failed instead of returning quietly.
            try {
                val vodCategories = categoryNames(server, user, password, "get_vod_categories")
                val vodStreams = readArray(api(server, user, password, "get_vod_streams"))
                var added = 0
                for (i in 0 until vodStreams.length()) {
                    if (added >= MAX_PER_CATEGORY) {
                        notes.add("נטענו $MAX_PER_CATEGORY סרטים מתוך ${vodStreams.length()}")
                        break
                    }
                    val item = vodStreams.optJSONObject(i) ?: continue
                    val streamId = item.opt("stream_id")?.toString()?.takeIf { it.isNotBlank() }
                        ?: continue
                    val ext = item.optString("container_extension").ifBlank { "mp4" }
                    val url = "$server/movie/$creds/$streamId.$ext"
                    val name = item.optString("name").trim().ifEmpty { "סרט $streamId" }
                    channels.add(
                        Channel(
                            id = M3uParser.channelId(url, name),
                            name = name,
                            url = url,
                            kind = ChannelKind.VOD,
                            group = vodCategories[item.opt("category_id")?.toString()],
                            logo = item.optString("stream_icon").ifBlank { null },
                            alias = aliasOf(item, name),
                        )
                    )
                    added++
                }
            } catch (e: Exception) {
                notes.add("ספריית הסרטים לא נטענה: ${e.message ?: "שגיאה לא ידועה"}")
            }

            try {
                val seriesCategories = categoryNames(server, user, password, "get_series_categories")
                val seriesList = readArray(api(server, user, password, "get_series"))
                for (i in 0 until seriesList.length()) {
                    if (series.size >= MAX_SERIES) {
                        notes.add("נטענו $MAX_SERIES סדרות מתוך ${seriesList.length()}")
                        break
                    }
                    val item = seriesList.optJSONObject(i) ?: continue
                    val id = item.opt("series_id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
                    series.add(
                        Series(
                            id = id,
                            name = item.optString("name").trim().ifEmpty { "סדרה $id" },
                            logo = item.optString("cover").ifBlank { null },
                            group = seriesCategories[item.opt("category_id")?.toString()],
                            alias = aliasOf(item, item.optString("name").trim()),
                        )
                    )
                }
            } catch (e: Exception) {
                notes.add("רשימת הסדרות לא נטענה: ${e.message ?: "שגיאה לא ידועה"}")
            }
        }

        if (channels.isEmpty() && series.isEmpty()) {
            throw Http.HttpException("הפורטל לא החזיר ערוצים")
        }

        return ParsedPlaylist(
            channels = channels,
            epgUrl = "$server/xmltv.php?username=${encode(user)}&password=${encode(password)}",
            series = series,
            notes = notes,
        )
    }

    /**
     * Episodes are fetched per series, on demand: a portal with thousands of
     * series would need thousands of requests to expand them all up front.
     */
    fun loadEpisodes(
        source: PlaylistSource.Xtream,
        seriesId: String,
        seriesName: String? = null,
    ): List<Channel> {
        val server = normalizeServer(source.server)
        val url = api(server, source.username, source.password, "get_series_info") +
            "&series_id=${encode(seriesId)}"

        val info = try {
            JSONObject(Http.fetchText(url))
        } catch (e: Exception) {
            throw Http.HttpException("לא הצלחתי לטעון את פרקי הסדרה")
        }

        return parseEpisodes(info, server, credentials(source.username, source.password), seriesName)
    }

    /**
     * What to call an episode. Portals already write the numbering into the
     * title more often than not — "ניתוק - S01E04 - האתה שאתה" — and prefixing
     * our own produced "S1E4 · ניתוק - S01E04 - האתה שאתה" on screen. So the
     * series name is trimmed off the front when it is repeated there, and the
     * numbering is added only when the title does not already carry it.
     */
    private val EPISODE_MARK = Regex("s\\s?\\d{1,2}\\s?e\\s?\\d{1,3}", RegexOption.IGNORE_CASE)
    private val HEBREW_EPISODE_MARK = Regex("פרק\\s*\\d")
    private val LEADING_SEPARATOR = Regex("^\\s*[-–—·:|]\\s*")

    /**
     * What an episode is actually called.
     *
     * Portals name an episode by repeating everything you already know: the
     * series, then the season and number, then — sometimes — the title. Inside a
     * series, under a card that already says "episode 4", all of that is noise,
     * and it is what pushes the one useful word off the end of the line.
     */
    private val LEADING_SXXEXX = Regex("^S\\d{1,3}\\s*E\\d{1,4}\\b[\\s\\-–—·:|]*", RegexOption.IGNORE_CASE)
    private val LEADING_NxN = Regex("^\\d{1,3}x\\d{1,4}\\b[\\s\\-–—·:|]*", RegexOption.IGNORE_CASE)
    private val LEADING_JOIN = Regex("^[\\s\\-–—·:|]+")

    fun episodeLabel(name: String, seriesName: String?): String {
        var out = name.trim()
        if (out.isEmpty()) return out
        val series = seriesName?.trim().orEmpty()
        if (series.isNotEmpty() && out.startsWith(series, ignoreCase = true)) {
            out = LEADING_JOIN.replace(out.substring(series.length), "")
        }
        out = LEADING_SXXEXX.replace(out, "")
        out = LEADING_NxN.replace(out, "")
        // Nothing left but the numbering: the number is on the card already, so
        // the original is better than an empty line.
        return out.trim().ifEmpty { name.trim() }
    }

    fun episodeName(title: String, season: String, number: String, seriesName: String?): String {
        var name = title.trim()
        val series = seriesName?.trim().orEmpty()
        if (series.isNotEmpty() && name.startsWith(series, ignoreCase = true)) {
            name = LEADING_SEPARATOR.replace(name.substring(series.length), "").trim()
        }
        if (name.isEmpty()) name = "פרק $number"
        if (EPISODE_MARK.containsMatchIn(name) || HEBREW_EPISODE_MARK.containsMatchIn(name)) {
            return name
        }
        return "S${season}E$number · $name"
    }

    /** Split out from the request so the shape of a portal's reply can be tested. */
    fun parseEpisodes(
        info: JSONObject,
        server: String,
        credentials: String,
        seriesName: String? = null,
    ): List<Channel> {
        val seasons = info.optJSONObject("episodes") ?: return emptyList()
        val episodes = ArrayList<Channel>()

        // Season keys are strings ("1", "2", …) and arrive in no particular order.
        val seasonNumbers = seasons.keys().asSequence().toList()
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }

        for (season in seasonNumbers) {
            val list = seasons.optJSONArray(season) ?: continue
            for (i in 0 until list.length()) {
                val episode = list.optJSONObject(i) ?: continue
                val id = episode.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val ext = episode.optString("container_extension").ifBlank { "mp4" }
                val number = episode.opt("episode_num")?.toString()?.takeIf { it.isNotBlank() }
                    ?: "${i + 1}"
                val title = episode.optString("title").trim()
                val streamUrl = "$server/series/$credentials/$id.$ext"
                val name = episodeName(title, season, number, seriesName)
                episodes.add(
                    Channel(
                        id = M3uParser.channelId(streamUrl, name),
                        name = name,
                        url = streamUrl,
                        kind = ChannelKind.VOD,
                        group = "עונה $season",
                        logo = episode.optJSONObject("info")
                            ?.optString("movie_image")
                            ?.ifBlank { null },
                    )
                )
            }
        }

        return episodes
    }

    fun normalizeServer(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        val withScheme =
            if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed
            else "http://$trimmed"
        return withScheme.replace(Regex("/player_api\\.php.*$", RegexOption.IGNORE_CASE), "")
    }

    private fun credentials(username: String, password: String): String =
        "${encode(username)}/${encode(password)}"

    private fun api(server: String, user: String, password: String, action: String?): String {
        val base = "$server/player_api.php?username=${encode(user)}&password=${encode(password)}"
        return if (action == null) base else "$base&action=$action"
    }

    private fun categoryNames(
        server: String,
        user: String,
        password: String,
        action: String,
    ): Map<String, String> = try {
        val array = readArray(api(server, user, password, action))
        buildMap {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.opt("category_id")?.toString() ?: continue
                val name = item.optString("category_name")
                if (name.isNotBlank()) put(id, name)
            }
        }
    } catch (e: Exception) {
        // Names are cosmetic; a channel without its category is still watchable.
        emptyMap()
    }

    private fun readArray(url: String): JSONArray {
        val body = Http.fetchText(url)
        return try {
            JSONArray(body)
        } catch (e: Exception) {
            throw Http.HttpException("הפורטל לא החזיר JSON תקין — בדוק את הכתובת ואת הפרטים")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
