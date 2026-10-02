package dev.droidtop.display

import android.app.Activity
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager

/** Window flags shared by the companion's activity and presentation hosts. */
object SecondScreenWindowFlags {
    /** Companion surfaces take touch input, while key and pad input stay with the shell. */
    fun touchOnly(includeAltFocusableIm: Boolean = false): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (includeAltFocusableIm) WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM else 0)
}

/**
 * What a touch-only second-screen ACTIVITY (the companion, the idle cover) does when Android makes
 * it the top resumed activity, which is exactly when the system sends pad and keyboard presses to
 * its display (docs/SPEC.md 4c, "A touch-only activity never leaves a key without a window").
 *
 * Android sends a key to the focused window of the top-focused display. When that display's
 * focused application has no focusable window (a `FLAG_NOT_FOCUSABLE` activity), the dispatcher
 * waits 5 s for one and then reports "droidtop isn't responding" (console, build 1386: an ANR
 * naming CompanionActivity at 15:55:44). So while one of these activities is the top one it is
 * focusable, and it hands the pad straight back to the shell when the shell is in front on
 * another screen. When it stops being the top one it is touch-only again, so a tap on it never
 * moves the system's focused display away from the shell (Droidtop/tracker#186).
 */
object TouchOnlySurfaceFocus {
    /**
     * Installed by `:app`: brings the shell's task to the front when the shell is resumed on a
     * display other than `fromDisplayId`. Returns whether it did.
     */
    @Volatile
    var returnPadToShell: ((fromDisplayId: Int?) -> Boolean)? = null

    /** Installed by `:app`: delivers a key into the shell's own window. Returns whether a shell took it. */
    @Volatile
    var forwardKeyToShell: ((KeyEvent) -> Boolean)? = null

    fun onTopResumedChanged(activity: Activity, isTop: Boolean, displayId: Int?) {
        if (!isTop) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            return
        }
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val handed = runCatching { returnPadToShell?.invoke(displayId) ?: false }.getOrDefault(false)
        Log.i(
            "droidtop.SecondScreen",
            "${activity.javaClass.simpleName} on display $displayId is the top activity: " +
                if (handed) "pad handed back to the shell" else "no shell in front on another screen, keys stop here",
        )
    }

    /**
     * A key that reached the surface while it was the top activity. The shell gets it when there is
     * one; otherwise the surface swallows the pad, D-pad and Back keys (it has nothing a key could
     * drive, and Back would finish it) and leaves every other key (volume, media) to the system.
     */
    fun consumesKey(event: KeyEvent): Boolean {
        if (runCatching { forwardKeyToShell?.invoke(event) ?: false }.getOrDefault(false)) return true
        val code = event.keyCode
        return KeyEvent.isGamepadButton(code) ||
            code == KeyEvent.KEYCODE_BACK ||
            code == KeyEvent.KEYCODE_ENTER ||
            code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER
    }
}
