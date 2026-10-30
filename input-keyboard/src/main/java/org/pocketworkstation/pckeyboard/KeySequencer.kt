package org.pocketworkstation.pckeyboard

/**
 * Presses keys as a hardware keyboard would (Droidtop/tracker#772): the one place a chord is spelled out, where
 * macros, the slab and the second-screen keyboard each built the same press and release sequence. [send] takes an
 * Android keycode and whether it goes down.
 */
class KeySequencer(private val send: (keyCode: Int, down: Boolean) -> Unit) {
    /** [modifiers] down in order, [keyCode] down and up, then the modifiers up in reverse. */
    fun chord(keyCode: Int, modifiers: List<Int> = emptyList()) {
        hold(keyCode, modifiers)
        release(keyCode, modifiers)
    }

    /** [modifiers] down, then [keyCode] down; a [repeat] presses only the key again, the modifiers being held. */
    fun hold(keyCode: Int, modifiers: List<Int> = emptyList(), repeat: Boolean = false) {
        if (!repeat) modifiers.forEach { send(it, true) }
        send(keyCode, true)
    }

    /** [keyCode] up, then [modifiers] up in reverse: what [hold] pressed, let go. */
    fun release(keyCode: Int, modifiers: List<Int> = emptyList()) {
        send(keyCode, false)
        modifiers.asReversed().forEach { send(it, false) }
    }
}
