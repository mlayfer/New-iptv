package com.mlayfer.iptv

import com.mlayfer.iptv.data.HomeRows
import com.mlayfer.iptv.data.M3uParser
import com.mlayfer.iptv.data.Playback
import com.mlayfer.iptv.data.StreamVariants
import com.mlayfer.iptv.data.XtreamClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The Android app and the Tizen app are two implementations of one behaviour.
 * Both read the same fixtures — tizen/test/parity.test.js runs these exact
 * cases — so a change made on one side and forgotten on the other fails here.
 */
class ParityTest {

    private val fixtures: JSONObject by lazy {
        val candidates = listOf(
            File("../shared/parity/fixtures.json"),
            File("shared/parity/fixtures.json"),
            File("../../shared/parity/fixtures.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("fixtures not found; looked in ${candidates.map { it.absolutePath }}")
        JSONObject(file.readText())
    }

    @Test
    fun `parses a playlist the way the contract describes`() {
        val spec = fixtures.getJSONObject("m3u")
        val parsed = M3uParser.parse(spec.getString("playlist")).channels
        val expected = spec.getJSONArray("expected")

        assertEquals(expected.length(), parsed.size)
        for (i in 0 until expected.length()) {
            val want = expected.getJSONObject(i)
            val got = parsed[i]
            assertEquals(want.getString("name"), got.name)
            // The Kotlin model leaves the group unset; both apps display the
            // same fallback, so compare what the user would see.
            assertEquals(want.getString("group"), got.group ?: M3uParser.NO_GROUP)
            assertEquals(want.getString("kind"), got.kind.name)
            assertEquals(want.getString("url"), got.url)
            assertEquals(want.optString("userAgent").ifEmpty { null }, got.userAgent)
        }
    }

    @Test
    fun `normalizes portal addresses`() {
        val cases = fixtures.getJSONArray("servers")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            assertEquals(
                case.getString("input"),
                case.getString("expected"),
                XtreamClient.normalizeServer(case.getString("input")),
            )
        }
    }

    @Test
    fun `offers the same endpoint variants`() {
        val cases = fixtures.getJSONArray("variants")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val expected = case.getJSONArray("expected").let { array ->
                (0 until array.length()).map { array.getString(it) }
            }
            assertEquals(case.getString("url"), expected, StreamVariants.of(case.getString("url")))
        }
    }

    @Test
    fun `builds the same episode list`() {
        val spec = fixtures.getJSONObject("episodes")
        val episodes = XtreamClient.parseEpisodes(
            info = spec.getJSONObject("info"),
            server = spec.getString("server"),
            credentials = "${spec.getString("user")}/${spec.getString("pass")}",
        )
        val expected = spec.getJSONArray("expected")

        assertEquals(expected.length(), episodes.size)
        for (i in 0 until expected.length()) {
            val want = expected.getJSONObject(i)
            assertEquals(want.getString("name"), episodes[i].name)
            assertEquals(want.getString("group"), episodes[i].group)
            assertEquals(want.getString("url"), episodes[i].url)
        }
    }

    @Test
    fun `lays out the same home screen`() {
        val spec = fixtures.getJSONObject("home")
        val items = spec.getJSONArray("items").let { array ->
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                HomeRows.Card(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    group = o.getString("group"),
                    kind = o.getString("kind"),
                    contentType = o.getString("contentType"),
                )
            }
        }
        val history = spec.getJSONArray("history").let { array ->
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                HomeRows.Entry(
                    id = o.getString("id"),
                    at = o.getLong("at"),
                    position = o.getLong("position"),
                    duration = o.getLong("duration"),
                )
            }
        }
        val favorites = spec.getJSONArray("favorites").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }

        val rows = HomeRows.build(items, history, favorites)
        val expected = spec.getJSONArray("expected")

        assertEquals(expected.length(), rows.size)
        for (i in 0 until expected.length()) {
            val want = expected.getJSONObject(i)
            assertEquals(want.getString("key"), rows[i].key)
            assertEquals(want.getString("title"), rows[i].title)
            val wantItems = want.getJSONArray("items")
            assertEquals(wantItems.length(), rows[i].items.size)
            val wantResume = want.getJSONArray("resumeAt")
            for (j in 0 until wantItems.length()) {
                assertEquals(wantItems.getString(j), rows[i].items[j].id)
                assertEquals(wantResume.getLong(j), rows[i].items[j].resumeAt)
            }
        }
    }

    @Test
    fun `searches the whole catalogue the same way`() {
        val spec = fixtures.getJSONObject("search")
        val items = spec.getJSONArray("items").let { array ->
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                HomeRows.Card(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    group = o.getString("group"),
                    kind = o.getString("kind"),
                    contentType = o.getString("contentType"),
                )
            }
        }
        val cases = spec.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val query = case.getString("query")
            val rows = HomeRows.search(items, query)
            val expected = case.getJSONArray("expected")
            assertEquals(query, expected.length(), rows.size)
            for (j in 0 until expected.length()) {
                val want = expected.getJSONObject(j)
                assertEquals(query, want.getString("key"), rows[j].key)
                assertEquals(query, want.getString("title"), rows[j].title)
                val wantItems = want.getJSONArray("items")
                assertEquals(query, wantItems.length(), rows[j].items.size)
                for (k in 0 until wantItems.length()) {
                    assertEquals(query, wantItems.getString(k), rows[j].items[k].id)
                }
            }
        }
    }

    @Test
    fun `does the same playback arithmetic`() {
        val spec = fixtures.getJSONObject("playback")

        val seeks = spec.getJSONArray("seeks")
        for (i in 0 until seeks.length()) {
            val c = seeks.getJSONObject(i)
            assertEquals(
                "${c.getLong("position")} ${c.getLong("delta")} of ${c.getLong("duration")}",
                c.getLong("expected"),
                Playback.seekTarget(c.getLong("position"), c.getLong("delta"), c.getLong("duration")),
            )
        }

        val clocks = spec.getJSONArray("clocks")
        for (i in 0 until clocks.length()) {
            val c = clocks.getJSONObject(i)
            assertEquals(c.getString("expected"), Playback.formatClock(c.getLong("seconds")))
        }

        val steps = spec.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val c = steps.getJSONObject(i)
            val ids = c.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }
            val want = if (c.isNull("expected")) null else c.getString("expected")
            assertEquals(want, Playback.stepInList(ids, c.getString("id"), c.getInt("step")))
        }
    }

    @Test
    fun `keeps one history entry per item, newest first`() {
        val merged = HomeRows.mergeHistory(
            listOf(HomeRows.Entry("a", at = 2, position = 10), HomeRows.Entry("b", at = 1)),
            HomeRows.Entry("b", at = 3, position = 90, duration = 1200),
        )
        assertEquals(listOf("b", "a"), merged.map { it.id })
        assertEquals(90L, merged[0].position)
    }
}
