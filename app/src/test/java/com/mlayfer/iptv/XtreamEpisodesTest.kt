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
        assertEquals("S1E2 · פרק 2", episodes[1].name)
        assertTrue(episodes[1].url.endsWith("13.mp4"))

        assertEquals("S2E1 · Return", episodes[2].name)
        assertTrue(episodes[2].url.endsWith("991.mkv"))
    }

    @Test
    fun `a portal with no episodes is not an error`() {
        assertTrue(XtreamClient.parseEpisodes(JSONObject("{}"), "http://p", "u/p").isEmpty())
    }
}
