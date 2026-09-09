package com.mlayfer.iptv

import com.mlayfer.iptv.data.StreamVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamVariantsTest {

    @Test
    fun `offers the other endpoints of an xtream channel`() {
        val hls = "http://panel.example.com:80/live/USER/PASS/26989.m3u8"
        val variants = StreamVariants.of(hls)

        assertEquals(hls, variants.first())
        assertTrue(variants.contains("http://panel.example.com:80/live/USER/PASS/26989.ts"))
        assertTrue(variants.contains("http://panel.example.com:80/live/USER/PASS/26989"))
        // Older panels drop the /live/ segment entirely.
        assertTrue(variants.contains("http://panel.example.com:80/USER/PASS/26989"))
    }

    @Test
    fun `keeps the query string, which usually carries the token`() {
        assertTrue(
            StreamVariants.of("http://h/live/u/p/12.m3u8?token=abc")
                .contains("http://h/live/u/p/12.ts?token=abc")
        )
    }

    @Test
    fun `leaves real playlist URLs alone`() {
        // Not a stream id: rewriting these would only produce dead requests.
        assertEquals(listOf("https://cdn.tv/live/index.m3u8"), StreamVariants.of("https://cdn.tv/live/index.m3u8"))
        assertEquals(1, StreamVariants.of("https://cdn.tv/hls/stream_720p.m3u8").size)
    }

    @Test
    fun `works in both directions`() {
        assertTrue(StreamVariants.of("http://h/live/u/p/44.ts").contains("http://h/live/u/p/44.m3u8"))
    }
}
