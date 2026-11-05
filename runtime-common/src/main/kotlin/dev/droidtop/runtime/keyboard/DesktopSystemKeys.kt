package dev.droidtop.runtime.keyboard

import android.view.InputDevice
import android.view.KeyEvent

/**
 * The keys Android keeps from every app, passed to the Desktop session while it is in front (docs/SPEC.md 6b "Where
 * Meta goes", Droidtop/tracker#697; owner, 2026-11-05: "Desktop uses droidtop's accessibility key filter while a
 * Desktop session is in front, so Super, Alt+Tab and similar keys reach the session. It is off everywhere else").
 *
 * Android's PhoneWindowManager consumes Meta and its chords, shows Recents for Alt+Tab and switches the keyboard
 * layout on Ctrl+Space before any app sees them, unless the focused window holds a signature permission. An
 * accessibility service's key filter sees a key before that, so droidtop's one service ([AccessibilityHost]) takes
 * exactly these keys from a keyboard and hands them to the session's own key path ([sink]), which types them into
 * the container as any other key. Nothing else is taken, so every other key still reaches droidtop's windows as
 * before. With no session in front ([sink] null) the filter is not asked for, and nothing is taken.
 */
object DesktopSystemKeys {
    private const val OWNER = "desktop-system-keys"

    private val filter = Filter()

    /**
     * The session in front: the Desktop shell sets it while its session's input is live and its activity resumed,
     * and clears it after. Main thread, where the accessibility service's key events arrive too.
     */
    @Volatile
    var sink: ((KeyEvent) -> Boolean)? = null
        set(value) {
            field = value
            filter.reset()
            AccessibilityHost.wantKeyFilter(OWNER, value != null)
        }

    /** The service's key filter: true when [event] went to the session in front. */
    fun offer(event: KeyEvent): Boolean {
        val target = sink ?: return false
        if (!event.isFromSource(InputDevice.SOURCE_KEYBOARD) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return false
        if (!filter.take(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.metaState)) return false
        target(event)
        return true
    }

    /**
     * Which keys are taken, as plain ints so a JVM test drives it. A key's release is taken when its press was, so
     * Super+E's E, released after Super, does not reach Android half pressed.
     */
    class Filter {
        private val held = HashSet<Int>()

        fun take(keyCode: Int, down: Boolean, metaState: Int): Boolean {
            if (!down) return held.remove(keyCode)
            if (!isSystemKey(keyCode, metaState)) return false
            held += keyCode
            return true
        }

        fun reset() = held.clear()

        companion object {
            /** Meta itself, any Meta chord, Alt+Tab (with or without Shift), Ctrl+Space and the All apps key. Pure. */
            fun isSystemKey(keyCode: Int, metaState: Int): Boolean = when {
                keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT -> true
                metaState and KeyEvent.META_META_ON != 0 -> true
                keyCode == KeyEvent.KEYCODE_TAB && metaState and KeyEvent.META_ALT_ON != 0 -> true
                keyCode == KeyEvent.KEYCODE_SPACE && metaState and KeyEvent.META_CTRL_ON != 0 -> true
                keyCode == KeyEvent.KEYCODE_ALL_APPS -> true
                else -> false
            }
        }
    }
}
