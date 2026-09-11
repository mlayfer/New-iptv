package com.mlayfer.iptv

import com.mlayfer.iptv.data.ChannelKind
import com.mlayfer.iptv.data.Filtering
import com.mlayfer.iptv.data.M3uParser
import com.mlayfer.iptv.data.XmltvParser
import com.mlayfer.iptv.data.XtreamClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

    private val playlist = """
        #EXTM3U x-tvg-url="https://guide.example.com/epg.xml.gz"
        #EXTINF:-1 tvg-id="kan11.il" tvg-name="כאן 11" tvg-logo="https://logo/kan.png" group-title="ישראל",כאן 11 HD
        #EXTVLCOPT:http-user-agent=Mozilla/5.0 Player
        https://cdn.example.com/kan11/index.m3u8
        #EXTINF:-1 tvg-id="keshet12.il",קשת 12
        #EXTGRP:ישראל
        https://cdn.example.com/keshet/playlist.m3u8?token=a,b
        #EXTINF:0,Movie Without Attrs
        http://vod.example.com/movies/great-film.mp4
        #EXTINF:-1 tvg-id="dup",כאן 11 HD
        https://cdn.example.com/kan11/index.m3u8
        # stray comment
        not-a-url
    """.trimIndent()

    @Test
    fun `parses attributes, groups and duplicates`() {
        val parsed = M3uParser.parse(playlist)

        assertEquals("https://guide.example.com/epg.xml.gz", parsed.epgUrl)
        assertEquals(3, parsed.channels.size)

        val first = parsed.channels[0]
        assertEquals("כאן 11 HD", first.name)
        assertEquals("kan11.il", first.tvgId)
        assertEquals("ישראל", first.group)
        assertEquals("Mozilla/5.0 Player", first.userAgent)
        assertEquals(ChannelKind.LIVE, first.kind)

        // A comma inside the URL must not truncate it, and #EXTGRP fills the group in.
        assertTrue(parsed.channels[1].url.endsWith("token=a,b"))
        assertEquals("ישראל", parsed.channels[1].group)

        assertEquals(ChannelKind.VOD, parsed.channels[2].kind)
        assertEquals("ישראל" to 2, M3uParser.groups(parsed.channels).first())
    }

    @Test
    fun `filters by query, kind and favorites`() {
        val channels = M3uParser.parse(playlist).channels

        assertEquals(1, Filtering.apply(channels, query = "קשת").size)
        assertEquals(2, Filtering.apply(channels, kind = ChannelKind.LIVE).size)
        assertEquals(1, Filtering.apply(channels, group = "ללא קטגוריה").size)

        val favorite = channels[0].id
        val favorites = Filtering.apply(channels, favoritesOnly = true, favorites = setOf(favorite))
        assertEquals(listOf(favorite), favorites.map { it.id })

        val recent = Filtering.apply(channels, recentOrder = listOf(channels[2].id, channels[0].id))
        assertEquals(listOf(channels[2].id, channels[0].id), recent.map { it.id })
    }

    @Test
    fun `reads xmltv times, entities and the current programme`() {
        val now = 1_788_967_800_000L // 2026-09-09T15:30:00Z
        val xml = """
            <tv>
            <programme start="20260909180000 +0300" stop="20260909190000 +0300" channel="kan11.il">
              <title lang="he">חדשות &amp; מזג אוויר</title><desc><![CDATA[מהדורה מרכזית]]></desc>
            </programme>
            <programme start="20260909190000 +0300" stop="20260909200000 +0300" channel="kan11.il">
              <title>הסרט של הערב</title>
            </programme>
            <programme start="20200101120000 +0200" stop="20200101130000 +0200" channel="old.il">
              <title>ישן</title>
            </programme>
            </tv>
        """.trimIndent()

        assertEquals(1_788_967_800_000L, XmltvParser.parseTime("20260909183000 +0300"))

        val epg = XmltvParser.parse(xml, now)
        assertEquals(setOf("kan11.il"), epg.keys)
        assertEquals("חדשות & מזג אוויר", XmltvParser.programmeAt(epg["kan11.il"], now)?.title)
        assertEquals("מהדורה מרכזית", XmltvParser.programmeAt(epg["kan11.il"], now)?.desc)
        assertEquals("הסרט של הערב", XmltvParser.nextProgramme(epg["kan11.il"], now)?.title)
    }

    @Test
    fun `normalizes xtream server addresses`() {
        assertEquals("http://portal.example.com:8080", XtreamClient.normalizeServer("portal.example.com:8080/"))
        assertEquals("https://p.tv", XtreamClient.normalizeServer("https://p.tv/player_api.php?x=1"))
    }

    /**
     * The subscription this app is actually used with ships no XMLTV file, so
     * the guide is whatever `get_short_epg` says — base64 in some fields, two
     * different date formats, and the entries in no particular order.
     */
    @Test
    fun `reads the portal's own guide`() {
        val body = """
            {"epg_listings":[
              {"id":"2","title":"15TXodeo15gg16nXnCDXlNei16jXkQ==","start_timestamp":"1757607200","stop_timestamp":"1757614400"},
              {"id":"1","title":"15fXk9ep15XXqiDXlNei16jXkQ==","description":"157XlNeT15XXqNeUINee16jXm9eW15nXqiDXotedINee15LXmdep15nXnQ==","start_timestamp":"1757596400","stop_timestamp":"1757603600"},
              {"id":"3","title":"","start_timestamp":"1757614400"}
            ]}
        """.trimIndent()

        val parsed = XtreamClient.parseShortEpg(body)

        // Sorted by start, and the entry with no title at all is not a
        // programme — it is a hole in the guide.
        assertEquals(listOf("חדשות הערב", "הסרט של הערב"), parsed.map { it.title })
        assertEquals(1757596400_000L, parsed[0].start)
        assertEquals(1757603600_000L, parsed[0].stop)
        assertEquals("מהדורה מרכזית עם מגישים", parsed[0].desc)
        assertEquals(null, parsed[1].desc)
    }

    @Test
    fun `a portal that answers with nothing is not a crash`() {
        assertEquals(emptyList<Any>(), XtreamClient.parseShortEpg(""))
        assertEquals(emptyList<Any>(), XtreamClient.parseShortEpg("<html>403</html>"))
        assertEquals(emptyList<Any>(), XtreamClient.parseShortEpg("""{"epg_listings":[]}"""))
    }
}
