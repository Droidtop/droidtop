package org.pocketworkstation.pckeyboard

import android.os.Handler
import android.os.Looper

/**
 * Hold-to-repeat for a destination that does not repeat a held key itself (Droidtop/tracker#772: the touch keyboard
 * and the slab each carried their own constants and their own timer). The timing is a hardware keyboard's default: a
 * pause, then about twenty a second.
 */
object KeyRepeat {
    const val AFTER_MS = 400L
    const val EVERY_MS = 50L
}

/**
 * A timer for held keys: [start]'s action runs after `delayMs`, then every `intervalMs` until [cancel], or only once
 * when `intervalMs` is 0. Starting again cancels what was pending.
 */
interface KeyTimer {
    fun start(delayMs: Long, intervalMs: Long, action: () -> Unit)

    fun cancel()
}

/** [KeyTimer] on the main thread's [Handler]. */
class HandlerKeyTimer : KeyTimer {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    override fun start(delayMs: Long, intervalMs: Long, action: () -> Unit) {
        cancel()
        val run = object : Runnable {
            override fun run() {
                action()
                if (intervalMs > 0) handler.postDelayed(this, intervalMs) else pending = null
            }
        }
        pending = run
        handler.postDelayed(run, delayMs)
    }

    override fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }
}
