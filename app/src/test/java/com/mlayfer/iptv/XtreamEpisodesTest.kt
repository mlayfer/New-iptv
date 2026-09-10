package com.mlayfer.iptv

import com.mlayfer.iptv.data.ChannelKind
import com.mlayfer.iptv.data.XtreamClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class XtreamEpisodesTest {

    private val response = JSONObject(
        """
        {"info":{"name":"Some Show"},
         "episodes":{
           "2":[{"id":"991","episode_num":1,"title":"Return","container_extension":"mkv",
                 "info":{"movie_image":"http://img/2.jpg"}}],
           "1":[{"id":"12","episode_num":1,"title":"Pilot","container_extension":"mp4"},
                {"id":"13","episode_num":2,"title":"","container_extension":""}]
          }}
        """.trimIndent()
    )

    @Test
    fun `builds playable episodes in season order`() {
        val episodes = XtreamClient.parseEpisodes(response, "http://panel:8080", "user/pass")

        // The portal listed season 2 first; the list must still read in order.
        assertEquals(3, episodes.size)
        assertEquals("S1E1 · Pilot", episodes[0].name)
        assertEquals("http://panel:8080/series/user/pass/12.mp4", episodes[0].url)
        assertEquals("עונה 1", episodes[0].group)
        assertEquals(ChannelKind.VOD, episodes[0].kind)

        // Missing title falls back to the episode number, missing extension to mp4.
        // "פרק 2" already numbers itself, so nothing is prefixed to it.
        assertEquals("פרק 2", episodes[1].name)
        assertTrue(episodes[1].url.endsWith("13.mp4"))

        assertEquals("S2E1 · Return", episodes[2].name)
        assertTrue(episodes[2].url.endsWith("991.mkv"))
    }

    @Test
    fun `a title that already numbers itself is left alone`() {
        val response = JSONObject(
            """
            {"episodes":{"1":[
               {"id":"7","episode_num":4,"title":"ניתוק - S01E04 - האתה שאתה","container_extension":"mkv"}
             ]}}
            """.trimIndent()
        )
        val episodes = XtreamClient.parseEpisodes(response, "http://p", "u/p", seriesName = "ניתוק")

        // The portal writes the series name and the numbering into the title;
        // adding ours on top produced "S1E4 · ניתוק - S01E04 - האתה שאתה".
        assertEquals("S01E04 - האתה שאתה", episodes[0].name)
    }

    @Test
    fun `a portal with no episodes is not an error`() {
        assertTrue(XtreamClient.parseEpisodes(JSONObject("{}"), "http://p", "u/p").isEmpty())
    }
}
