package com.mlayfer.iptv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Talks to an Xtream Codes portal and normalizes it into the same channel model. */
object XtreamClient {

    private const val MAX_CHANNELS = 25_000

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

        val creds = "${encode(user)}/${encode(password)}"
        val channels = ArrayList<Channel>()

        val liveCategories = categoryNames(server, user, password, "get_live_categories")
        val liveStreams = readArray(api(server, user, password, "get_live_streams"))
        for (i in 0 until liveStreams.length()) {
            if (channels.size >= MAX_CHANNELS) break
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

        if (source.includeVod && channels.size < MAX_CHANNELS) {
            val vodCategories = categoryNames(server, user, password, "get_vod_categories")
            val vodStreams = try {
                readArray(api(server, user, password, "get_vod_streams"))
            } catch (e: Exception) {
                JSONArray()
            }
            for (i in 0 until vodStreams.length()) {
                if (channels.size >= MAX_CHANNELS) break
                val item = vodStreams.optJSONObject(i) ?: continue
                val streamId = item.opt("stream_id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
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
            }
        }

        if (channels.isEmpty()) throw Http.HttpException("הפורטל לא החזיר ערוצים")

        return ParsedPlaylist(
            channels = channels,
            epgUrl = "$server/xmltv.php?username=${encode(user)}&password=${encode(password)}",
        )
    }

    fun normalizeServer(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        val withScheme =
            if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed
            else "http://$trimmed"
        return withScheme.replace(Regex("/player_api\\.php.*$", RegexOption.IGNORE_CASE), "")
    }

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
