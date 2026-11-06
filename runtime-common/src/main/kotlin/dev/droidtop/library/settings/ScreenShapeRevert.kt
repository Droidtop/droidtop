package dev.droidtop.library.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A change to the screen's shape that undoes itself unless kept (Droidtop/tracker#786 plan 4.x, S14): font size,
 * display size, and later rotation, refresh rate and screen roles. [propose] is called once the change is made, with
 * what puts it back; [Pending] is drawn as a Keep bar where the change was made, and after [SECONDS] with no Keep the
 * change is put back, so a size that leaves the screen unusable fixes itself. One at a time: a new change keeps the
 * one before it (its own undo goes back further than the person meant).
 */
object ScreenShapeRevert {
    const val SECONDS = 10

    /** The change waiting for Keep: what it was ("Font size 130%") and when it goes back, in wall-clock millis. */
    class Pending internal constructor(val label: String, val deadlineMillis: Long, internal val revert: suspend () -> Unit)

    private val state = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timer: Job? = null

    /** Whole seconds left before [deadlineMillis], never below 0, rounded up so the bar never shows 0 while waiting. Pure. */
    fun secondsLeft(deadlineMillis: Long, nowMillis: Long): Int =
        ((deadlineMillis - nowMillis + 999) / 1000).coerceAtLeast(0).toInt()

    /** The change just made, called [label]; [revert] puts it back after [SECONDS] unless [keep] comes first. */
    @Synchronized
    fun propose(label: String, now: Long = System.currentTimeMillis(), revert: suspend () -> Unit) {
        timer?.cancel()
        val next = Pending(label, now + SECONDS * 1000L, revert)
        state.value = next
        timer = scope.launch {
            delay(SECONDS * 1000L)
            if (take(next)) runCatching { next.revert() }
        }
    }

    /** Keeps the waiting change. */
    @Synchronized
    fun keep() {
        timer?.cancel()
        state.value = null
    }

    /** Puts the waiting change back now. */
    fun revertNow() {
        val current = state.value ?: return
        if (take(current)) {
            timer?.cancel()
            scope.launch { runCatching { current.revert() } }
        }
    }

    @Synchronized
    private fun take(expected: Pending): Boolean {
        if (state.value !== expected) return false
        state.value = null
        return true
    }
}
