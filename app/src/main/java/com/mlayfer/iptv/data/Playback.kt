package com.mlayfer.iptv.data

/**
 * Playback arithmetic, shared so the two players behave identically: where a
 * seek lands, what the clock reads, and which item comes next in a series.
 * tizen/core.js holds the same rules, and shared/parity/ checks them together.
 */
object Playback {

    const val SEEK_STEP = 10L
    const val SEEK_STEP_LONG = 60L

    fun seekTarget(position: Long, delta: Long, duration: Long): Long {
        var target = position + delta
        if (target < 0) target = 0
        // Landing exactly on the end restarts or stalls depending on the player,
        // so stop just short of it.
        if (duration > 0 && target > duration - 1) target = maxOf(0, duration - 1)
        return target
    }

    fun formatClock(seconds: Long): String {
        val s = if (seconds < 0) 0 else seconds
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** The neighbour of what is playing, or null at either end of the season. */
    fun stepInList(ids: List<String>, currentId: String, step: Int): String? {
        val at = ids.indexOf(currentId)
        if (at == -1) return null
        return ids.getOrNull(at + step)
    }
}
