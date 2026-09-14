package com.mlayfer.iptv.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Typing a portal password on a remote control.
 *
 * Twelve random characters, one letter at a time, on an on-screen keyboard that
 * has to be driven with four arrows and an OK — this is the single worst thing
 * the app asks anyone to do, and it is the first thing it asks.
 *
 * So the television stops asking. It puts a small web server on the local
 * network, shows the address to it as a QR code, and the phone that scans it
 * gets an ordinary form with an ordinary keyboard. What is typed there is
 * posted straight back to the television.
 *
 * Nothing leaves the house: there is no service in the middle, no account, and
 * nothing stored anywhere but on the television, exactly as before. The price
 * is that while the code is on screen, the form is reachable by anything else
 * on the same network — so the address carries a secret that is thrown away
 * after one use, and the server stops the moment the code leaves the screen.
 */
object Pairing {

    /** What the phone sent: the same four things the form on the TV asks for. */
    data class Handoff(
        val server: String,
        val username: String,
        val password: String,
        val includeVod: Boolean,
    )

    /** A running server, and the address to put in front of a camera. */
    class Session internal constructor(
        val url: String,
        private val socket: ServerSocket,
    ) {
        @Volatile
        internal var stopped = false

        fun stop() {
            stopped = true
            runCatching { socket.close() }
        }
    }

    /**
     * No vowels and no characters that look like each other: the secret never
     * has to be read out or typed, but a QR that fails to scan is read off the
     * screen by hand, and that is when 0 and O cost an evening.
     */
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    private fun secret(length: Int = 10): String =
        (1..length).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")

    /**
     * The address of this device on the network the phone is on.
     *
     * Enumerated rather than asked of the Wi-Fi service, because an Android TV
     * box is usually on Ethernet, and because enumerating needs no permission.
     */
    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /**
     * Start listening. Returns null when there is no network to listen on —
     * which is worth saying out loud rather than showing a code that cannot
     * work.
     *
     * [onHandoff] is called on the server's own thread.
     */
    fun start(
        prefillServer: String = "",
        onHandoff: (Handoff) -> Unit,
    ): Session? {
        val address = localAddress() ?: return null
        val socket = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        val token = secret()
        val session = Session("http://$address:${socket.localPort}/$token", socket)

        thread(isDaemon = true, name = "pairing") {
            while (!session.stopped) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { serve(client, token, prefillServer, onHandoff) }
                runCatching { client.close() }
            }
        }
        return session
    }

    private fun serve(
        client: Socket,
        token: String,
        prefillServer: String,
        onHandoff: (Handoff) -> Unit,
    ) {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val request = reader.readLine() ?: return
        val (method, path) = requestLine(request) ?: return

        var length = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val header = line.substringBefore(':').trim().lowercase()
            if (header == "content-length") {
                length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        val out = client.getOutputStream()
        // The secret is the whole of the protection: anything that does not
        // carry it is told nothing at all, not even that it guessed the port.
        if (path.trimStart('/').substringBefore('?') != token) {
            respond(out, "404 Not Found", "text/plain; charset=utf-8", "לא נמצא")
            return
        }

        when (method) {
            "GET" -> respond(out, "200 OK", "text/html; charset=utf-8", page(token, prefillServer))
            "POST" -> {
                val body = CharArray(length.coerceIn(0, 8192))
                val read = if (body.isEmpty()) 0 else reader.read(body, 0, body.size)
                val form = parseForm(String(body, 0, read.coerceAtLeast(0)))
                val handoff = Handoff(
                    server = form["server"].orEmpty().trim(),
                    username = form["username"].orEmpty().trim(),
                    password = form["password"].orEmpty(),
                    includeVod = form["vod"] != null,
                )
                if (handoff.server.isBlank() || handoff.username.isBlank()) {
                    respond(out, "400 Bad Request", "text/html; charset=utf-8", done(false))
                    return
                }
                respond(out, "200 OK", "text/html; charset=utf-8", done(true))
                out.flush()
                onHandoff(handoff)
            }
            else -> respond(out, "405 Method Not Allowed", "text/plain; charset=utf-8", "")
        }
    }

    /** "POST /abc HTTP/1.1" → POST and /abc. Null for anything that is not that. */
    fun requestLine(line: String): Pair<String, String>? {
        val parts = line.split(' ')
        if (parts.size < 2) return null
        return parts[0].uppercase() to parts[1]
    }

    /** An HTML form's body: pairs, `+` for a space, and percent escapes. */
    fun parseForm(body: String): Map<String, String> =
        body.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val at = pair.indexOf('=')
            val key = if (at >= 0) pair.substring(0, at) else pair
            val value = if (at >= 0) pair.substring(at + 1) else ""
            runCatching {
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }.getOrNull()
        }.toMap()

    private fun respond(out: OutputStream, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            // Nothing here should be kept by anything: it is a password form
            // that exists for one minute.
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * The form, as a phone should meet it: one column, large targets, the
     * keyboard told not to capitalise or correct a username, and no request of
     * any kind to anywhere else — the page is the whole of it.
     */
    fun page(token: String, prefillServer: String = ""): String = """
<!doctype html>
<html lang="he" dir="rtl">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>טלוהים — התחברות</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; padding: 24px 18px 40px; background: #0d0f14; color: #eef1f6;
         font: 17px/1.5 -apple-system, "Segoe UI", Roboto, Arial, sans-serif; }
  h1 { font-size: 22px; margin: 0 0 4px; }
  p.sub { margin: 0 0 22px; color: #9aa3b2; font-size: 15px; }
  label { display: block; margin: 16px 0 6px; font-size: 15px; color: #c7cede; }
  input[type=text], input[type=password] {
    width: 100%; box-sizing: border-box; padding: 14px; border-radius: 12px;
    border: 1px solid #2a3040; background: #151925; color: #eef1f6; font-size: 17px; }
  .row { display: flex; align-items: center; gap: 10px; margin: 20px 0 4px; }
  .row input { width: 22px; height: 22px; }
  button { width: 100%; margin-top: 26px; padding: 16px; border: 0; border-radius: 12px;
           background: #3b82f6; color: #fff; font-size: 18px; font-weight: 700; }
  .note { margin-top: 20px; color: #7b8496; font-size: 13px; }
</style>
<h1>התחברות לפורטל</h1>
<p class="sub">מה שתקליד כאן יעבור לטלוויזיה. שום דבר לא נשלח לשום מקום אחר.</p>
<form method="post" action="/$token">
  <label for="server">כתובת השרת</label>
  <input id="server" name="server" type="text" inputmode="url" autocapitalize="off"
         autocorrect="off" spellcheck="false" placeholder="http://example.com:80"
         value="${escape(prefillServer)}">
  <label for="username">שם משתמש</label>
  <input id="username" name="username" type="text" autocapitalize="off"
         autocorrect="off" spellcheck="false">
  <label for="password">סיסמה</label>
  <input id="password" name="password" type="password">
  <div class="row"><input id="vod" name="vod" type="checkbox" checked>
    <label for="vod" style="margin:0">לכלול סרטים וסדרות</label></div>
  <button type="submit">שלח לטלוויזיה</button>
</form>
<p class="note">הדף הזה חי רק כל עוד הקוד מוצג על המסך, והוא זמין רק ברשת הביתית.</p>
</html>
""".trimIndent()

    private fun done(ok: Boolean): String = """
<!doctype html>
<html lang="he" dir="rtl">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>טלוהים</title>
<style>
  body { margin: 0; display: flex; align-items: center; justify-content: center;
         min-height: 100vh; background: #0d0f14; color: #eef1f6; text-align: center;
         font: 19px/1.6 -apple-system, "Segoe UI", Roboto, Arial, sans-serif; }
</style>
<div>${if (ok) "נשלח לטלוויזיה 👍<br><small>אפשר לסגור את הדף</small>" else "חסרים פרטים — חזור ומלא הכול"}</div>
</html>
""".trimIndent()
}
