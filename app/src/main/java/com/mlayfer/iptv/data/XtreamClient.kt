package com.mlayfer.iptv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Talks to an Xtream Codes portal and normalizes it into the same channel model. */
object XtreamClient {

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
    fun loadEpisodes(source: PlaylistSource.Xtream, seriesId: String): List<Channel> {
        val server = normalizeServer(source.server)
        val url = api(server, source.username, source.password, "get_series_info") +
            "&series_id=${encode(seriesId)}"

        val info = try {
            JSONObject(Http.fetchText(url))
        } catch (e: Exception) {
            throw Http.HttpException("לא הצלחתי לטעון את פרקי הסדרה")
        }

        return parseEpisodes(info, server, credentials(source.username, source.password))
    }

    /** Split out from the request so the shape of a portal's reply can be tested. */
    fun parseEpisodes(info: JSONObject, server: String, credentials: String): List<Channel> {
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
                val title = episode.optString("title").trim().ifEmpty { "פרק $number" }
                val streamUrl = "$server/series/$credentials/$id.$ext"
                episodes.add(
                    Channel(
                        id = M3uParser.channelId(streamUrl, title),
                        name = "S${season}E$number · $title",
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
