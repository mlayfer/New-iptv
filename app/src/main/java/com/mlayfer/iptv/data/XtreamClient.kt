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

    /**
     * @param onStage told which part of the catalogue is being fetched. A real
     *   subscription is twenty thousand items over three calls and takes the
     *   better part of a minute, and a screen that says nothing for that long is
     *   indistinguishable from one that has hung.
     */
    fun load(source: PlaylistSource.Xtream, onStage: (String) -> Unit = {}): ParsedPlaylist {
        onStage("מתחבר לפורטל…")
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

        onStage("טוען ערוצים…")
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
                    streamId = streamId,
                )
            )
        }

        if (source.includeVod) {
            // A portal that refuses one catalogue must not look like a portal that
            // has none: say what failed instead of returning quietly.
            try {
                onStage("טוען סרטים…")
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
                onStage("טוען סדרות…")
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
     * series, then the season and number, then — sometimes — the title. Under a
     * card that already says "episode 4", inside a page that already says which
     * series, all of that is noise, and it is what pushes the one useful word
     * off the end of the line.
     *
     * The numbering is the landmark, not the name: a portal that lists
     * "Severance (2022)" happily calls its episodes "Severance - S01E01 - ...",
     * so matching the series title never fires. Whatever sits before S01E01 is
     * the series in some spelling; whatever follows it is the episode.
     */
    private val NUMBERING = Regex("""S\s?\d{1,3}\s?E\s?\d{1,4}|\b\d{1,3}x\d{1,4}\b""", RegexOption.IGNORE_CASE)
    private val LEADING_JOIN = Regex("""^[\s\-–—·:|]+""")

    fun episodeLabel(name: String, seriesName: String?): String {
        val out = name.trim()
        if (out.isEmpty()) return out

        val mark = NUMBERING.find(out)
        if (mark != null) {
            val after = LEADING_JOIN.replace(out.substring(mark.range.last + 1), "").trim()
            // Nothing after the numbering: the number is on the card already,
            // but an empty line is worse than a repeated one.
            return after.ifEmpty { out }
        }

        val series = seriesName?.trim().orEmpty()
        if (series.isNotEmpty() && out.startsWith(series, ignoreCase = true)) {
            val rest = LEADING_JOIN.replace(out.substring(series.length), "").trim()
            if (rest.isNotEmpty()) return rest
        }
        return out
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

    /**
     * The guide for one channel.
     *
     * An XMLTV dump is a file the subscription may or may not come with, and
     * this one does not: the portal is the guide. `get_short_epg` answers with
     * the next few programmes for a single stream, which is exactly the amount
     * the screen shows — and it is cheap enough to ask for the channel under the
     * cursor rather than for thirteen thousand of them up front.
     */
    fun shortEpg(source: PlaylistSource.Xtream, streamId: String, limit: Int = 6): List<Programme> {
        val server = normalizeServer(source.server)
        val url = api(server, source.username, source.password, "get_short_epg") +
            "&stream_id=${encode(streamId)}&limit=$limit"
        return parseShortEpg(Http.fetchText(url))
    }

    /** Split out from the request so a portal's shape can be tested without one. */
    fun parseShortEpg(body: String): List<Programme> {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return emptyList()
        }
        val listings = root.optJSONArray("epg_listings")
            ?: root.optJSONArray("epg_listing")
            ?: return emptyList()

        val out = ArrayList<Programme>(listings.length())
        for (i in 0 until listings.length()) {
            val row = listings.optJSONObject(i) ?: continue
            // Panels base64 the title and the description, and not always both.
            val title = decodeMaybeBase64(row.optString("title")).trim()
            if (title.isEmpty()) continue
            val start = epgTime(row.opt("start_timestamp") ?: row.opt("start")) ?: continue
            val stop = epgTime(row.opt("stop_timestamp") ?: row.opt("end"))
                ?: (start + 3_600_000L)
            val desc = decodeMaybeBase64(row.optString("description")).trim().ifEmpty { null }
            out.add(Programme(start, stop, title, desc))
        }
        return out.sortedBy { it.start }
    }

    /**
     * Panels date a programme two different ways: a unix second, or a string
     * like `2026-09-11 17:30:00` carrying no zone at all. The string is read as
     * a local time, which is what the set does with it and what the portal
     * means by it.
     */
    private val PLAIN_TIME =
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?")

    private fun epgTime(value: Any?): Long? {
        val raw = value?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val asNumber = raw.toLongOrNull()
        if (asNumber != null) return if (asNumber > 100_000L) asNumber * 1000L else null

        val m = PLAIN_TIME.find(raw) ?: return null
        val calendar = java.util.Calendar.getInstance()
        calendar.clear()
        calendar.set(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt() - 1,
            m.groupValues[3].toInt(),
            m.groupValues[4].toInt(),
            m.groupValues[5].toInt(),
            m.groupValues[6].ifEmpty { "0" }.toInt(),
        )
        return calendar.timeInMillis
    }

    /**
     * Base64 if it decodes to something a person could read, otherwise the text
     * as it came: a title is not tagged, so the only test is whether it works.
     *
     * Decoded by hand rather than through `android.util.Base64`, which is a stub
     * that throws in a JVM test — and this is exactly the part worth testing,
     * because a portal that base64s half its fields is how a guide ends up full
     * of gibberish.
     */
    fun decodeMaybeBase64(value: String?): String {
        val raw = value?.toString().orEmpty()
        val clean = raw.filterNot { it.isWhitespace() }
        // Plenty of ordinary words are valid base64, so the guards matter: real
        // base64 comes in whole groups of four.
        if (clean.length < 8 || clean.length % 4 != 0) return raw
        if (!BASE64.matches(clean)) return raw

        val bytes = decodeBase64(clean) ?: return raw
        val text = String(bytes, Charsets.UTF_8)
        if (text.isBlank()) return raw
        // A decode that lands on control characters — or on the replacement
        // character, which is what invalid UTF-8 becomes — decoded something
        // that was never base64 to begin with.
        if (text.any { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 || it == '\uFFFD' }) {
            return raw
        }
        return text
    }

    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Null when the text is not base64 at all, rather than half a decode. */
    private fun decodeBase64(value: String): ByteArray? {
        val clean = value.trimEnd('=')
        if (clean.length % 4 == 1) return null

        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val index = ALPHABET.indexOf(c)
            if (index < 0) return null
            buffer = (buffer shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
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
