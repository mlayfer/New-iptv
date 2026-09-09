package com.mlayfer.iptv

import com.mlayfer.iptv.data.StreamProbe
import com.mlayfer.iptv.data.StreamProbe.BodyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamProbeTest {

    private fun sniff(text: String) = StreamProbe.sniff(text.toByteArray())

    @Test
    fun `identifies what the server actually sent`() {
        assertEquals(BodyKind.HLS_MASTER, sniff("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8"))
        assertEquals(BodyKind.HLS_MEDIA, sniff("#EXTM3U\n#EXTINF:6,\nseg.ts"))
        // Providers routinely serve playlists with a BOM in front.
        assertEquals(BodyKind.HLS_MEDIA, sniff("﻿#EXTM3U\n#EXTINF:6,\nseg.ts"))
        assertEquals(BodyKind.HTML, sniff("<!DOCTYPE html><html>gone</html>"))
        assertEquals(BodyKind.JSON, sniff("""{"error":"expired"}"""))
        assertEquals(BodyKind.EMPTY, StreamProbe.sniff(ByteArray(0)))

        val transportStream = ByteArray(400)
        transportStream[0] = 0x47
        transportStream[188] = 0x47
        assertEquals(BodyKind.MPEG_TS, StreamProbe.sniff(transportStream))

        val mp4 = ByteArray(64)
        "ftyp".toByteArray().copyInto(mp4, 4)
        assertEquals(BodyKind.MP4, StreamProbe.sniff(mp4))
    }

    @Test
    fun `resolves the first segment of a playlist`() {
        assertEquals(
            "https://cdn.tv/live/segments/001.ts",
            StreamProbe.firstUri("#EXTM3U\n#EXTINF:6,\nsegments/001.ts\n", "https://cdn.tv/live/index.m3u8"),
        )
        assertEquals(
            "https://other.tv/a.ts",
            StreamProbe.firstUri("#EXTM3U\n#EXTINF:6,\nhttps://other.tv/a.ts", "https://cdn.tv/x.m3u8"),
        )
        assertNull(StreamProbe.firstUri("#EXTM3U\n#EXT-X-ENDLIST", "https://cdn.tv/x.m3u8"))
    }

    @Test
    fun `explains each failure in terms a person can act on`() {
        fun summary(vararg steps: StreamProbe.Step) =
            StreamProbe.summarize(StreamProbe.Report(steps.toList()))

        assertTrue(summary(StreamProbe.Step("u", 403)).contains("חסימה"))
        assertTrue(summary(StreamProbe.Step("u", 404)).contains("הוסר"))
        assertTrue(summary(StreamProbe.Step("u", 503)).contains("תקלה אצלו"))
        assertTrue(summary(StreamProbe.Step("u", 200, kind = BodyKind.HTML)).contains("דף אינטרנט"))
        assertTrue(summary(StreamProbe.Step("u", failure = "UnknownHostException")).contains("אין תשובה"))

        // When the bytes are a real stream, the app must blame itself, not the provider.
        assertTrue(summary(StreamProbe.Step("u", 200, kind = BodyKind.MPEG_TS)).contains("הבעיה אצלנו"))

        // A playlist that loads while its segments are blocked has to say so.
        val segmentBlocked = summary(
            StreamProbe.Step("playlist", 200, kind = BodyKind.HLS_MEDIA),
            StreamProbe.Step("segment", 403),
        )
        assertTrue(segmentBlocked.contains("המקטע עצמו"))
    }
}
