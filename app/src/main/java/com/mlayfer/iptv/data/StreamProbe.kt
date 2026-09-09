package com.mlayfer.iptv.data

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the provider directly what it is actually serving, so a failure can name
 * its cause instead of guessing.
 *
 * A player error alone can't tell a removed channel from a blocked device from
 * a bug here: ExoPlayer reports "malformed container" whether the server sent an
 * HTML error page, a 403, or something it genuinely can't decode. The probe
 * fetches the first few kilobytes, follows an HLS playlist down to its first
 * real segment, and reports the status, the content type and what the bytes
 * actually are at each hop.
 */
object StreamProbe {

    enum class BodyKind { HLS_MASTER, HLS_MEDIA, MPEG_TS, MP4, HTML, JSON, EMPTY, UNKNOWN }

    data class Step(
        val url: String,
        val status: Int = 0,
        val contentType: String? = null,
        val kind: BodyKind = BodyKind.UNKNOWN,
        /** Set when the request never completed (DNS, refused, timeout, TLS). */
        val failure: String? = null,
    )

    data class Report(
        /** The playlist's own URL, followed down to its first segment. */
        val steps: List<Step>,
        /** One hop against each alternative endpoint of the same channel. */
        val variants: List<Step> = emptyList(),
    )

    private const val SNIFF_BYTES = 8 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /**
     * Blocking: call from a background dispatcher.
     *
     * [alternatives] are other endpoints for the same channel; each is checked
     * once so the report can show that, say, the HLS endpoint answers with an
     * error page while the MPEG-TS one serves video.
     */
    fun probe(
        url: String,
        userAgent: String = Http.DEFAULT_USER_AGENT,
        referrer: String? = null,
        maxHops: Int = 3,
        alternatives: List<String> = emptyList(),
    ): Report {
        val steps = ArrayList<Step>()
        var target = url

        for (hop in 0 until maxHops) {
            val (step, body) = fetch(target, userAgent, referrer)
            steps.add(step)

            if (step.failure != null || step.status !in 200..299) break
            if (step.kind != BodyKind.HLS_MASTER && step.kind != BodyKind.HLS_MEDIA) break

            // Follow the playlist down to the thing that actually carries video:
            // a manifest that loads fine while its segments 403 is a common and
            // otherwise invisible failure.
            target = firstUri(body ?: break, step.url) ?: break
        }

        val variants = alternatives
            .filter { it != url }
            .map { fetch(it, userAgent, referrer).first }

        return Report(steps, variants)
    }

    private fun fetch(
        rawUrl: String,
        userAgent: String,
        referrer: String?,
    ): Pair<Step, String?> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                // Never pull a whole live segment just to look at its first bytes.
                setRequestProperty("Range", "bytes=0-${SNIFF_BYTES - 1}")
                referrer?.let { setRequestProperty("Referer", it) }
            }

            val status = connection.responseCode
            val stream: InputStream? =
                if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.let { readSome(it) } ?: ByteArray(0)
            val kind = sniff(bytes)

            val step = Step(
                url = connection.url?.toString() ?: rawUrl,
                status = status,
                contentType = connection.contentType,
                kind = kind,
            )
            val body = if (kind == BodyKind.HLS_MASTER || kind == BodyKind.HLS_MEDIA) {
                String(bytes, Charsets.UTF_8)
            } else {
                null
            }
            step to body
        } catch (e: Exception) {
            Step(
                url = rawUrl,
                failure = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""),
            ) to null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readSome(input: InputStream): ByteArray {
        val buffer = ByteArray(SNIFF_BYTES)
        var read = 0
        try {
            while (read < SNIFF_BYTES) {
                val count = input.read(buffer, read, SNIFF_BYTES - read)
                if (count == -1) break
                read += count
            }
        } catch (e: Exception) {
            // Partial content is still enough to identify the format.
        } finally {
            runCatching { input.close() }
        }
        return buffer.copyOf(read)
    }

    fun sniff(bytes: ByteArray): BodyKind {
        if (bytes.isEmpty()) return BodyKind.EMPTY

        // MPEG-TS packets start with the 0x47 sync byte, every 188 bytes.
        if (bytes[0] == 0x47.toByte() && (bytes.size <= 188 || bytes[188] == 0x47.toByte())) {
            return BodyKind.MPEG_TS
        }
        if (bytes.size > 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp") return BodyKind.MP4

        val text = String(bytes, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            text.startsWith("#EXTM3U") && text.contains("#EXT-X-STREAM-INF") -> BodyKind.HLS_MASTER
            text.startsWith("#EXTM3U") -> BodyKind.HLS_MEDIA
            text.startsWith("<") -> BodyKind.HTML
            text.startsWith("{") || text.startsWith("[") -> BodyKind.JSON
            else -> BodyKind.UNKNOWN
        }
    }

    /** First real URI inside an HLS playlist, resolved against the playlist's own URL. */
    fun firstUri(playlist: String, baseUrl: String): String? {
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            return runCatching { URL(URL(baseUrl), line).toString() }.getOrNull()
        }
        return null
    }

    private fun Step.carriesVideo(): Boolean =
        status in 200..299 && (kind == BodyKind.MPEG_TS || kind == BodyKind.MP4)

    /** A sentence a person can act on. */
    fun summarize(report: Report): String {
        // If one of the other endpoints is serving video, the channel is alive and
        // the failure is ours to fix — say so instead of blaming the provider.
        report.variants.firstOrNull { it.carriesVideo() }?.let { working ->
            return "הכתובת שברשימה לא עובדת, אבל כתובת אחרת של אותו ערוץ כן מחזירה וידאו (${working.url}). אם ההודעה הזו מופיעה, זה באג אצלנו — שלח לי את הדוח."
        }

        val last = report.steps.lastOrNull()
            ?: return "לא הצלחתי לבדוק את הערוץ."

        last.failure?.let { failure ->
            return "אין תשובה מהשרת של הספק ($failure). ייתכן שהשרת מת, או שהרשת שלך חוסמת אותו — כדאי לנסות דרך רשת סלולרית או VPN."
        }

        val deep = report.steps.size > 1
        val where = if (deep) "המקטע עצמו" else "הכתובת"

        return when {
            last.status == 401 || last.status == 403 ->
                "הספק דחה את הבקשה ל$where (${last.status}). זו חסימה מצד הספק — מנוי שפג, הגבלת מכשירים, או חסימה גאוגרפית."

            last.status == 404 || last.status == 410 ->
                "$where כבר לא קיימת אצל הספק (${last.status}). הערוץ הוסר, והרשימה מיושנת."

            last.status in 500..599 ->
                "השרת של הספק מחזיר שגיאה (${last.status}). זו תקלה אצלו, לא אצלנו."

            last.status !in 200..299 ->
                "השרת החזיר סטטוס ${last.status} ל$where."

            last.kind == BodyKind.HTML ->
                "השרת החזיר דף אינטרנט במקום שידור — כמעט תמיד דף שגיאה, מסך התחברות או הפניה של הספק."

            last.kind == BodyKind.JSON ->
                "השרת החזיר JSON במקום שידור — בדרך כלל הודעת שגיאה של הפורטל."

            last.kind == BodyKind.EMPTY ->
                "השרת ענה בסדר אבל לא שלח שום תוכן."

            last.kind == BodyKind.MPEG_TS || last.kind == BodyKind.MP4 ->
                "השרת מחזיר וידאו תקין. אם הניגון בכל זאת נכשל, הבעיה אצלנו — שלח לי את הדוח."

            last.kind == BodyKind.HLS_MASTER || last.kind == BodyKind.HLS_MEDIA ->
                "הפלייליסט תקין, אבל לא הצלחתי להגיע ממנו למקטע וידאו. אם זה חוזר, שלח לי את הדוח."

            else ->
                "השרת החזיר תוכן שלא זיהיתי (content-type: ${last.contentType ?: "לא צוין"})."
        }
    }

    /** The full chain, for pasting into a bug report. */
    fun technical(report: Report): String {
        val lines = StringBuilder()
        report.steps.forEachIndexed { index, step -> lines.append(render(index + 1, step)) }
        if (report.variants.isNotEmpty()) {
            lines.appendLine("כתובות חלופיות:")
            report.variants.forEachIndexed { index, step -> lines.append(render(index + 1, step)) }
        }
        return lines.toString().trimEnd()
    }

    private fun render(number: Int, step: Step): String {
        val tail = step.failure?.let { "   ✗ $it" }
            ?: "   ${step.status} · ${step.contentType ?: "ללא content-type"} · ${step.kind}"
        return "$number. ${step.url}\n$tail\n"
    }
}
