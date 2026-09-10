package com.mlayfer.iptv.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Two devices, one memory.
 *
 * The naive answer — whichever device wrote last wins — throws away whatever
 * the other one did in the meantime. So nothing is merged as a whole: every
 * item carries its own timestamp and is merged on its own. A film marked on the
 * television and a film marked on the phone both survive, and unmarking on one
 * device beats an older mark on the other, because "off" is a value with a time
 * on it and not the absence of one.
 *
 * The wire format is shared with tizen/core.js, item for item, because both
 * write to the same row.
 */
object Sync {

    const val VERSION = 1
    private const val HISTORY_CAP = 60
    private const val FLAG_CAP = 4000

    /** A decision with a time on it, so a later one anywhere wins. */
    data class Flag(val at: Long, val on: Boolean)

    data class Doc(
        val history: List<RecentEntry> = emptyList(),
        val favorites: Map<String, Flag> = emptyMap(),
        val watched: Map<String, Flag> = emptyMap(),
    )

    /** Ids switched on right now, newest decision first. */
    fun flagsOn(map: Map<String, Flag>): List<String> =
        map.entries.asSequence()
            .filter { it.value.on }
            .sortedWith(compareByDescending<Map.Entry<String, Flag>> { it.value.at }.thenBy { it.key })
            .map { it.key }
            .toList()

    /** Turn a plain list of ids into timestamped flags, for a first upgrade. */
    fun flagsFrom(ids: List<String>, at: Long): Map<String, Flag> {
        val map = LinkedHashMap<String, Flag>()
        ids.forEachIndexed { index, id ->
            // Older entries sit further back in time, so a later toggle wins.
            if (id.isNotBlank()) map[id] = Flag(at - (ids.size - index), true)
        }
        return map
    }

    fun mergeFlags(a: Map<String, Flag>, b: Map<String, Flag>): Map<String, Flag> {
        val out = HashMap<String, Flag>()
        fun take(map: Map<String, Flag>) {
            for ((id, cell) in map) {
                val seen = out[id]
                // A tie keeps "on": losing a mark is worse than keeping a stale one.
                if (seen == null || cell.at > seen.at || (cell.at == seen.at && cell.on)) {
                    out[id] = cell
                }
            }
        }
        take(a)
        take(b)

        return out.entries
            .sortedWith(compareByDescending<Map.Entry<String, Flag>> { it.value.at }.thenBy { it.key })
            .take(FLAG_CAP)
            .associate { it.key to it.value }
    }

    fun mergeHistories(a: List<RecentEntry>, b: List<RecentEntry>): List<RecentEntry> {
        val byId = HashMap<String, RecentEntry>()
        fun take(list: List<RecentEntry>) {
            for (entry in list) {
                if (entry.channelId.isBlank()) continue
                val seen = byId[entry.channelId]
                if (seen == null || entry.at > seen.at) byId[entry.channelId] = entry
            }
        }
        take(a)
        take(b)

        return byId.values
            .sortedWith(compareByDescending<RecentEntry> { it.at }.thenBy { it.channelId })
            .take(HISTORY_CAP)
    }

    fun mergeDocs(local: Doc, remote: Doc): Doc = Doc(
        history = mergeHistories(local.history, remote.history),
        favorites = mergeFlags(local.favorites, remote.favorites),
        watched = mergeFlags(local.watched, remote.watched),
    )

    /**
     * What may leave the device.
     *
     * A history entry carries a snapshot of the card so an episode survives a
     * reload, and on the Tizen side that snapshot carries the portal's address,
     * username and password. None of that belongs on a sync server, so the
     * fields that travel are listed rather than excluded: a whitelist that
     * forgets a field loses a poster, a blacklist that forgets one leaks a
     * password. The playlist id stays home too — it names a row in this
     * device's own store and means nothing on another.
     */
    fun toJson(doc: Doc): String {
        val history = JSONArray()
        for (entry in doc.history) {
            val card = JSONObject()
                .put("id", entry.channelId)
                .put("name", entry.name)
                .put("group", entry.group)
                .put("kind", entry.kind)
                .put("contentType", entry.contentType)
            entry.logo?.let { card.put("logo", it) }
            history.put(
                JSONObject()
                    .put("id", entry.channelId)
                    .put("at", entry.at)
                    .put("position", entry.position)
                    .put("duration", entry.duration)
                    .put("card", card)
            )
        }
        return JSONObject()
            .put("v", VERSION)
            .put("history", history)
            .put("favorites", flagsToJson(doc.favorites))
            .put("watched", flagsToJson(doc.watched))
            .toString()
    }

    private fun flagsToJson(map: Map<String, Flag>): JSONObject {
        val out = JSONObject()
        for ((id, cell) in map) {
            out.put(id, JSONObject().put("at", cell.at).put("on", cell.on))
        }
        return out
    }

    /**
     * Read a document written by either app. `playlistId` is supplied by the
     * caller because it is this device's own name for the source.
     */
    fun fromJson(raw: String, playlistId: String): Doc {
        return try {
            val root = JSONObject(raw)
            val array = root.optJSONArray("history") ?: JSONArray()
            val history = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                val card = o.optJSONObject("card")
                RecentEntry(
                    channelId = id,
                    playlistId = playlistId,
                    at = o.optLong("at"),
                    position = o.optLong("position"),
                    duration = o.optLong("duration"),
                    name = card?.optString("name") ?: "",
                    group = card?.optString("group") ?: "",
                    kind = card?.optString("kind") ?: "",
                    contentType = card?.optString("contentType") ?: "",
                    logo = card?.optString("logo")?.ifBlank { null },
                )
            }
            Doc(
                history = history,
                favorites = flagsFromJson(root.optJSONObject("favorites")),
                watched = flagsFromJson(root.optJSONObject("watched")),
            )
        } catch (e: Exception) {
            // A document we cannot read is treated as one we have not got yet;
            // the local memory is never thrown away over a parse error.
            Doc()
        }
    }

    private fun flagsFromJson(obj: JSONObject?): Map<String, Flag> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Flag>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val cell = obj.optJSONObject(id) ?: continue
            out[id] = Flag(cell.optLong("at"), cell.optBoolean("on"))
        }
        return out
    }
}
