package com.mlayfer.iptv

import com.mlayfer.iptv.data.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the handover that is not a socket: what a browser sends, and what
 * it is sent back. A phone is the only client this server will ever have, and
 * it is one that cannot be asked to try again differently.
 */
class PairingTest {

    @Test
    fun `reads the method and the path a browser asks for`() {
        assertEquals("GET" to "/abc123", Pairing.requestLine("GET /abc123 HTTP/1.1"))
        assertEquals("POST" to "/abc123", Pairing.requestLine("POST /abc123 HTTP/1.1"))
        assertNull(Pairing.requestLine("nonsense"))
    }

    @Test
    fun `decodes a form the way a browser encodes one`() {
        val form = Pairing.parseForm("server=http%3A%2F%2Fa.test%3A80&username=a+b&password=p%26q&vod=on")
        assertEquals("http://a.test:80", form["server"])
        // A space arrives as "+", and a password is free to contain anything.
        assertEquals("a b", form["username"])
        assertEquals("p&q", form["password"])
        assertEquals("on", form["vod"])
    }

    @Test
    fun `an unticked checkbox is absent rather than false`() {
        val form = Pairing.parseForm("server=x&username=y&password=z")
        assertNull(form["vod"])
    }

    @Test
    fun `survives a body that is empty or malformed`() {
        assertTrue(Pairing.parseForm("").isEmpty())
        assertEquals(mapOf("a" to ""), Pairing.parseForm("a"))
    }

    @Test
    fun `the page posts back to the secret it was reached at`() {
        val page = Pairing.page("tok3n", prefillServer = "http://portal.test:80")
        assertTrue(page.contains("""action="/tok3n""""))
        for (field in listOf("server", "username", "password", "vod")) {
            assertTrue(field, page.contains("""name="$field""""))
        }
        // What the television already knows is not worth typing again.
        assertTrue(page.contains("""value="http://portal.test:80""""))
    }

    @Test
    fun `an address with a quote in it cannot break out of the field`() {
        val page = Pairing.page("t", prefillServer = """http://a"><script>x</script>""")
        assertTrue(page.contains("&quot;"))
        assertTrue(!page.contains("<script>"))
    }
}
