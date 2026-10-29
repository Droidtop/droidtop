package dev.droidtop.mapping

/**
 * Two pad buttons held together for [holdMs], as a host's own switch (docs/SPEC.md 5b, "Controller mouse mode": both
 * stick clicks held 0.6 s turn mouse mode on or off). Pure: [press] and [release] report the buttons, by Android key
 * code ([PadInputs]), and [due] says, at a time, whether both have been held long enough and this hold has not fired
 * yet. A host asks [due] on its events and once more after [holdMs], since a held key sends nothing further.
 */
class HoldCombo(private val first: Int, private val second: Int, val holdMs: Long) {
    private var firstSince: Long? = null
    private var secondSince: Long? = null
    private var fired = false

    fun press(keyCode: Int, now: Long) {
        when (keyCode) {
            first -> if (firstSince == null) firstSince = now
            second -> if (secondSince == null) secondSince = now
        }
    }

    fun release(keyCode: Int) {
        when (keyCode) {
            first -> firstSince = null
            second -> secondSince = null
        }
        if (firstSince == null || secondSince == null) fired = false
    }

    fun due(now: Long): Boolean {
        val since = maxOf(firstSince ?: return false, secondSince ?: return false)
        if (fired || now - since < holdMs) return false
        fired = true
        return true
    }

    companion object {
        /** Mouse mode's switch: both stick clicks, held 0.6 s (no game holds both for this long). */
        fun stickClicks() = HoldCombo(PadInputs.KEYCODE_BUTTON_THUMBL, PadInputs.KEYCODE_BUTTON_THUMBR, 600L)
    }
}
