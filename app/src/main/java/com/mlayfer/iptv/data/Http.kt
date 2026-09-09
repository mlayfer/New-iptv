package com.mlayfer.iptv.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Plain HTTP helpers. A native app talks to providers directly: no CORS, no proxy. */
object Http {

    const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    private const val MAX_BYTES = 64 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    class HttpException(message: String) : Exception(message)

    fun fetchText(rawUrl: String, userAgent: String = DEFAULT_USER_AGENT): String {
        var url = parseUrl(rawUrl)
        var redirects = 0

        while (redirects <= MAX_REDIRECTS) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                // The JDK refuses to follow http -> https on its own, and providers
                // redirect across schemes all the time.
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "*/*")

                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) throw HttpException("הפניה לא תקינה מהשרת")
                    url = URL(url, location)
                    redirects++
                    continue
                }
                if (status !in 200..299) throw HttpException("השרת החזיר שגיאה $status")

                val bytes = readCapped(connection.inputStream)
                val decoded = if (isGzip(bytes)) gunzip(bytes) else bytes
                return String(decoded, Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }

        throw HttpException("יותר מדי הפניות מהשרת")
    }

    private fun parseUrl(rawUrl: String): URL {
        val url = try {
            URL(rawUrl.trim())
        } catch (e: Exception) {
            throw HttpException("כתובת לא תקינה")
        }
        if (url.protocol != "http" && url.protocol != "https") {
            throw HttpException("נתמכות רק כתובות http/https")
        }
        return url
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    private fun gunzip(bytes: ByteArray): ByteArray {
        val stream = GZIPInputStream(bytes.inputStream())
        try {
            return readCapped(stream)
        } finally {
            stream.close()
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream()
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (out.size() + read > MAX_BYTES) throw HttpException("הקובץ גדול מדי")
                out.write(buffer, 0, read)
            }
        } finally {
            input.close()
        }
        return out.toByteArray()
    }
}
