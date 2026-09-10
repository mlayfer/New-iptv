package com.mlayfer.iptv.data

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/**
 * Which soundtrack, and which subtitles.
 *
 * A film from a portal often carries Hebrew and the original language, and two
 * or three subtitle tracks with it. The player picks one by itself; this is how
 * a person picks a different one. The Tizen build offers the same two lists.
 */
object TrackChoices {

    /** One line in the list: what it is called, and whether it is on now. */
    data class Choice(
        val label: String,
        val groupIndex: Int,
        val trackIndex: Int,
        val selected: Boolean,
    )

    /**
     * A track's name, in the order a person would recognise it: what the stream
     * calls it, then its language, then nothing but a number.
     */
    fun labelOf(format: Format, ordinal: Int): String {
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { return languageName(it) }
        return "רצועה $ordinal"
    }

    /** The few languages a viewer here actually meets, said in Hebrew. */
    private fun languageName(code: String): String = when (code.lowercase().take(2)) {
        "he", "iw" -> "עברית"
        "en" -> "אנגלית"
        "ar" -> "ערבית"
        "ru" -> "רוסית"
        "fr" -> "צרפתית"
        "es" -> "ספרדית"
        "de" -> "גרמנית"
        "tr" -> "טורקית"
        else -> code
    }

    /** Every selectable track of one kind, in the order the stream lists them. */
    fun choicesFor(tracks: Tracks, type: Int): List<Choice> {
        val out = ArrayList<Choice>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                // A track the device cannot decode is not worth offering.
                if (!group.isTrackSupported(i)) continue
                out.add(
                    Choice(
                        label = labelOf(group.getTrackFormat(i), out.size + 1),
                        groupIndex = groupIndex,
                        trackIndex = i,
                        selected = group.isTrackSelected(i),
                    )
                )
            }
        }
        return out
    }

    /** True when subtitles are currently switched off altogether. */
    fun subtitlesOff(player: Player): Boolean =
        player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

    fun choose(player: Player, tracks: Tracks, type: Int, choice: Choice) {
        val group = tracks.groups.getOrNull(choice.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            // Choosing a subtitle is also how you turn subtitles back on.
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(choice.trackIndex))
            )
            .build()
    }

    /** Subtitles off: the override has to go too, or it switches them back on. */
    fun turnOff(player: Player, type: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, true)
            .build()
    }
}
