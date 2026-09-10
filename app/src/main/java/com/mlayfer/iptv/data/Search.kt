package com.mlayfer.iptv.data

/**
 * Matching a title someone half-remembers, in either language.
 *
 * Two things get in the way. Providers list a show under one name only —
 * "ניתוק" and never "Severance" — and when they do use the original name they
 * spell it in Hebrew letters ("ברייקינג באד"). The first is answered by any
 * original-title field the portal happens to send, kept next to the name. The
 * second is answered here: both sides are reduced to their consonants in one
 * shared alphabet, so a Latin query and a Hebrew spelling of the same sounds
 * meet in the middle. A translated name still cannot be guessed from the other
 * language, and nothing here pretends otherwise.
 *
 * tizen/core.js runs the same rules, and shared/parity/ checks them together.
 */
object Search {

    private const val SKELETON_MIN = 2
    private const val QUERY_MIN_FOR_SKELETON = 3

    private val HEBREW_LETTERS = Regex("[\\u0590-\\u05ff]")
    private val LATIN_LETTERS = Regex("[a-zA-Z]")

    /**
     * Which alphabet something is written in. The sound-alike path exists to
     * cross between alphabets; inside one alphabet it only removes information
     * — "ניתוק" and "האנטי קסם" share the consonants N-T-K and nothing else.
     */
    fun scriptOf(text: String): String {
        val hebrew = HEBREW_LETTERS.containsMatchIn(text)
        val latin = LATIN_LETTERS.containsMatchIn(text)
        return when {
            hebrew && latin -> "both"
            hebrew -> "he"
            latin -> "la"
            else -> "none"
        }
    }

    private val HEBREW = mapOf(
        'א' to "", 'ב' to "B", 'ג' to "G", 'ד' to "D", 'ה' to "", 'ו' to "",
        'ז' to "Z", 'ח' to "X", 'ט' to "T", 'י' to "", 'כ' to "K", 'ך' to "K",
        'ל' to "L", 'מ' to "M", 'ם' to "M", 'נ' to "N", 'ן' to "N", 'ס' to "S",
        'ע' to "", 'פ' to "P", 'ף' to "P", 'צ' to "C", 'ץ' to "C", 'ק' to "K",
        'ר' to "R", 'ש' to "S", 'ת' to "T",
    )

    private val LATIN = mapOf(
        'b' to "B", 'v' to "B", 'w' to "B", 'p' to "P", 'f' to "P", 'k' to "K",
        'c' to "K", 'q' to "K", 'g' to "G", 'j' to "G", 'd' to "D", 't' to "T",
        'z' to "Z", 's' to "S", 'r' to "R", 'l' to "L", 'm' to "M", 'n' to "N",
        'x' to "KS",
    )

    /**
     * Grammar words, and the Hebrew spellings of those same words as they appear
     * in transliterated titles ("גיים אוף ת'רונס"). Dropping them on both sides
     * keeps one spelling from hiding the other.
     */
    private val STOP_WORDS = setOf("the", "a", "an", "of", "and", "דה", "אוף", "אנד", "א")

    fun skeleton(text: String): String {
        var s = text.lowercase()
            .split(Regex("\\s+"))
            .filter { it !in STOP_WORDS }
            .joinToString(" ")

        // Digraphs first: they are one sound, and the letters would map wrongly.
        // A doubled vav is the consonant v; a single one is a vowel.
        s = s.replace("וו", "ב")
            .replace("ce", "se").replace("ci", "si")
            .replace("sh", "s").replace("ch", "x").replace("kh", "x")
            .replace("tz", "c").replace("ts", "c")
            .replace("ph", "f").replace("th", "t").replace("ck", "k").replace("qu", "k")

        val out = StringBuilder()
        for (ch in s) {
            when {
                ch in '0'..'9' -> out.append(ch)
                HEBREW.containsKey(ch) -> out.append(HEBREW.getValue(ch))
                LATIN.containsKey(ch) -> out.append(LATIN.getValue(ch))
                // Everything else — vowels, spaces, punctuation, ה and י —
                // carries no information about how a name was transliterated.
            }
        }

        // A doubled letter in one spelling is a single one in the other, and s
        // between vowels is heard as z: neither difference should hide a match.
        return out.toString().replace("Z", "S").replace(Regex("(.)\\1+"), "$1")
    }

    /** The text a search looks at: the name, its category, and any other title. */
    fun searchableText(name: String, group: String?, alias: String?): String =
        listOfNotNull(name, group, alias).filter { it.isNotBlank() }
            .joinToString(" ").lowercase()

    fun matches(text: String, query: String, querySkeleton: String? = null): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        if (text.contains(q)) return true

        // Only then the sound-alike path, and only for a query that is actually
        // a name: "i24" reduced to its consonants is the digits, which appear
        // inside half the channel numbers in a playlist.
        if (q.length < QUERY_MIN_FOR_SKELETON) return false
        if (q.count { it.isLetter() } < QUERY_MIN_FOR_SKELETON) return false
        val wanted = querySkeleton ?: skeleton(q)
        if (wanted.count { it in 'A'..'Z' } < SKELETON_MIN) return false

        // ...and only against text written in the other alphabet. Within one
        // alphabet the plain match above is the whole truth.
        val asked = scriptOf(q)
        val parts = text.split(Regex("\\s+")).filter {
            val kind = scriptOf(it)
            kind != "none" && kind != asked
        }
        if (parts.isEmpty()) return false
        return skeleton(parts.joinToString(" ")).contains(wanted)
    }
}
