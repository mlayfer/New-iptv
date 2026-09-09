package com.mlayfer.iptv

import com.mlayfer.iptv.data.M3uParser
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
}
