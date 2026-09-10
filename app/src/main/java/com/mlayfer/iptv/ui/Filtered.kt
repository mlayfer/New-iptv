package com.mlayfer.iptv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Filtering a catalogue, off the thread that draws it.
 *
 * A real subscription is twenty thousand items, and every one of them is walked
 * for every letter typed. Doing that between two frames is what made the app
 * stutter and then stop answering: the work is not wrong, it is merely in the
 * wrong place.
 *
 * So it moves to a background thread, and it waits a moment first — someone
 * typing "ניתוק" produces five searches, of which only the last one is ever
 * looked at. The previous pass is cancelled the moment the next letter lands.
 */
@Composable
fun <T> filteredAsync(
    vararg keys: Any?,
    /** Shown until the first pass finishes, so the list never blinks empty. */
    initial: List<T>,
    /** True while the query is short enough to answer instantly. */
    immediate: Boolean = false,
    compute: () -> List<T>,
): State<List<T>> = produceState(initialValue = initial, keys = keys) {
    // "Immediate" means no waiting, not "do it here": nineteen thousand items
    // are worth moving off the drawing thread even when the answer is easy.
    if (!immediate) delay(SETTLE_MS)
    value = withContext(Dispatchers.Default) { compute() }
}

/** Long enough to swallow a fast typist, short enough not to feel laggy. */
private const val SETTLE_MS = 180L
