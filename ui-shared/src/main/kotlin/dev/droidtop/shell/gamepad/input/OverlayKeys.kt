package dev.droidtop.shell.gamepad.input

import androidx.compose.runtime.compositionLocalOf

/**
 * Who a key belongs to while overlays open and close (docs/SPEC.md 6e,
 * "The overlay stack owns the keys", Droidtop/tracker#359).
 *
 * Every modal surface is a Compose `Dialog`, a window of its own, and
 * Android moves window focus to it a frame or more after the press that
 * opened it. In that gap the activity's gate still delivered keys to the
 * screen underneath, so the next press (the opening button's release, or a
 * quick D-pad press) landed on the library behind the menu. The stack is
 * pushed when the overlay's content composes, before its window has focus,
 * and from then on:
 *
 * - **A covered window gets nothing.** A window with an overlay above it
 *   drops every press, repeat and release (the window at [layerOf] 0 is the
 *   activity, the first overlay is 1, and so on).
 * - **A release goes where its press went.** The layer that took a DOWN is
 *   recorded; its UP and repeats are delivered to that layer only. An UP
 *   arriving at another layer (the opening A's release reaching the new
 *   menu, the closing B's release reaching the screen the menu was
 *   over) is swallowed, as is any key whose DOWN was itself swallowed.
 *
 * One instance per activity window, shared by its gate and by the dialogs
 * above it; pure (key codes only), so the unit test scripts a sequence with
 * no Android `KeyEvent`.
 */
class OverlayKeys {
    private val stack = ArrayList<Any>()
    private val holder = HashMap<Int, Int>()

    /** Whether any overlay is open. */
    val open: Boolean get() = stack.isNotEmpty()

    /** An overlay was pushed (idempotent per [token]); keys for the layers beneath it stop here. */
    fun push(token: Any) {
        if (stack.none { it === token }) stack.add(token)
    }

    /** The overlay is gone. */
    fun pop(token: Any) {
        stack.removeAll { it === token }
    }

    /** The layer [token] is: 1 for the first overlay; 0 (the activity) for a token that is not open. */
    fun layerOf(token: Any): Int = stack.indexOfFirst { it === token } + 1

    /** Lets go of every held key (the window lost the pad); the next fresh press starts clean. */
    fun forgetHeld() = holder.clear()

    /**
     * Whether a key event for the window at [layer] is delivered (true) or
     * swallowed (false). [repeatCount] is the platform's own.
     */
    fun route(layer: Int, keyCode: Int, down: Boolean, repeatCount: Int): Boolean {
        val covered = stack.size > layer
        if (down) {
            if (repeatCount == 0) {
                holder[keyCode] = if (covered) SWALLOWED else layer
                return !covered
            }
            return !covered && holder[keyCode] == layer
        }
        val took = holder.remove(keyCode)
        return !covered && (took == null || took == layer)
    }

    private companion object {
        const val SWALLOWED = -1
    }
}

/** The activity's [OverlayKeys], for the dialogs composed inside it; null outside the shell. */
val LocalOverlayKeys = compositionLocalOf<OverlayKeys?> { null }
