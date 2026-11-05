package dev.droidtop.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The first value at once, then each later value only once [quietMs] pass with no newer one, and never the same value
 * twice in a row. Attaching a screen sends a burst of display events, and the second-screen pass acted on each one
 * (Droidtop/tracker#785); this hands it the displays once they have settled, while the first pass is not delayed.
 */
fun <T> Flow<T>.settled(quietMs: Long): Flow<T> = channelFlow {
    var first = true
    collectLatest { value ->
        if (first) first = false else delay(quietMs)
        send(value)
    }
}.distinctUntilChanged()
