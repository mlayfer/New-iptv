package com.mlayfer.iptv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local persistence. Playlists (including portal credentials), favorites and
 * watch history stay on the device — nothing is uploaded anywhere.
 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("mask_hai", Context.MODE_PRIVATE)

    var playlists: List<Playlist>
        get() = readPlaylists()
        set(value) = prefs.edit().putString(KEY_PLAYLISTS, writePlaylists(value)).apply()

    var activeId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    var favorites: Set<String>
        get() = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FAVORITES, value).apply()

    var recent: List<RecentEntry>
        get() = readRecent()
        set(value) = prefs.edit().putString(KEY_RECENT, writeRecent(value)).apply()

    private fun readPlaylists(): List<Playlist> {
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<Playlist>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val source = item.optJSONObject("source") ?: continue
                val parsed = when (source.optString("kind")) {
                    "url" -> PlaylistSource.Url(source.optString("url"))
                    "text" -> PlaylistSource.Text(source.optString("content"))
                    "xtream" -> PlaylistSource.Xtream(
                        server = source.optString("server"),
                        username = source.optString("username"),
                        password = source.optString("password"),
                        includeVod = source.optBoolean("includeVod", true),
                    )
                    else -> null
                } ?: continue
                out.add(
                    Playlist(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        source = parsed,
                        epgUrl = item.optString("epgUrl").ifBlank { null },
                        createdAt = item.optLong("createdAt"),
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writePlaylists(playlists: List<Playlist>): String {
        val array = JSONArray()
        for (playlist in playlists) {
            val source = JSONObject()
            when (val s = playlist.source) {
                is PlaylistSource.Url -> {
                    source.put("kind", "url")
                    source.put("url", s.url)
                }
                is PlaylistSource.Text -> {
                    source.put("kind", "text")
                    source.put("content", s.content)
                }
                is PlaylistSource.Xtream -> {
                    source.put("kind", "xtream")
                    source.put("server", s.server)
                    source.put("username", s.username)
                    source.put("password", s.password)
                    source.put("includeVod", s.includeVod)
                }
            }
            array.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("epgUrl", playlist.epgUrl ?: "")
                    .put("createdAt", playlist.createdAt)
                    .put("source", source)
            )
        }
        return array.toString()
    }

    private fun readRecent(): List<RecentEntry> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<RecentEntry>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                out.add(
                    RecentEntry(
                        channelId = item.optString("channelId"),
                        playlistId = item.optString("playlistId"),
                        at = item.optLong("at"),
                        position = item.optLong("position"),
                        duration = item.optLong("duration"),
                        name = item.optString("name"),
                        group = item.optString("group"),
                        kind = item.optString("kind"),
                        contentType = item.optString("contentType"),
                        logo = item.optString("logo").ifBlank { null },
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeRecent(recent: List<RecentEntry>): String {
        val array = JSONArray()
        for (entry in recent) {
            array.put(
                JSONObject()
                    .put("channelId", entry.channelId)
                    .put("playlistId", entry.playlistId)
                    .put("at", entry.at)
                    .put("position", entry.position)
                    .put("duration", entry.duration)
                    .put("name", entry.name)
                    .put("group", entry.group)
                    .put("kind", entry.kind)
                    .put("contentType", entry.contentType)
                    .put("logo", entry.logo ?: "")
            )
        }
        return array.toString()
    }

    private companion object {
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_ACTIVE = "active"
        const val KEY_FAVORITES = "favorites"
        const val KEY_RECENT = "recent"
    }
}
