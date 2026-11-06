package org.pocketworkstation.pckeyboard

/**
 * Haptics per key class (Droidtop/tracker#903 H12a; docs/SPEC.md 6a, "Motion and haptics"): which class a key is, the
 * "Vibration strength" setting and its migration from Hacker's Keyboard's "Vibrate on keypress", and the effect each
 * class gets at each strength. Pure, so it is tested on the JVM; [KeyHapticsPlayer] turns these into the platform's
 * VibrationEffect objects.
 *
 * Ported in part:
 * - [amplitude] is FlorisBoard's `Vibrator.vibrate` amplitude rule (`lib/android/.../Vibrator.kt`,
 *   https://github.com/florisboard/florisboard at bac346e8, Apache-2.0): a strength in percent times a factor,
 *   as a share of 255, never below 1.
 * - The split of a predefined effect on Android 10 and later and a timed buzz below it, with each effect built once
 *   rather than per press, follows Unexpected Keyboard's `VibratorCompat`
 *   (https://github.com/Julow/Unexpected-Keyboard at 0695efdc, GPL-3.0).
 * The classes, the tables and the migration are droidtop's own.
 */
enum class HapticClass { LETTER, MODIFIER, DELETE, SPACE_ENTER }

/** The setting's values, stored by [id]; [percent] is the amplitude a buzz is given below Android 10. */
enum class VibrationStrength(val id: String, val percent: Int) {
    OFF("off", 0),
    LIGHT("light", 35),
    MEDIUM("medium", 65),
    STRONG("strong", 100),
    ;

    companion object {
        /** The strength stored as [id]; anything else (nothing stored, an unknown value) is [OFF]. */
        @JvmStatic
        fun of(id: String?): VibrationStrength = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** The platform's predefined effects (VibrationEffect.EFFECT_*, Android 10), named here so the tables stay pure. */
enum class HapticEffect { TICK, CLICK, HEAVY_CLICK, DOUBLE_CLICK }

/**
 * The `View.performHapticFeedback` constant a class gets where the keyboard follows Android's own touch feedback
 * setting (the keyboard droidtop embeds, as droidtop's text-entry slab does).
 */
enum class ViewHaptic { KEYBOARD_TAP, CONTEXT_CLICK, CLOCK_TICK, VIRTUAL_KEY }

/** A buzz below Android 10: off and on times in ms from the start, and each step's amplitude (0 off). */
class Waveform(val timings: LongArray, val amplitudes: IntArray) {
    /** One step on after no delay: playable as a one-shot. */
    val isOneShot: Boolean get() = timings.size == 2 && timings[0] == 0L
}

object KeyHaptics {
    /** The setting. It replaces Hacker's Keyboard's [OLD_ON] and [OLD_LEN], whose values [migrated] reads. */
    const val PREF = "vibrate_strength"
    const val OLD_ON = "vibrate_on"
    const val OLD_LEN = "vibrate_len"

    /** VibrationEffect.DEFAULT_AMPLITUDE: the vibrator's own, for one that cannot vary it. */
    const val DEFAULT_AMPLITUDE = -1

    /**
     * The class of a Hacker's Keyboard key code: Shift, Caps Lock, Ctrl, Alt, Meta, Fn and the symbols switch are
     * modifiers; Backspace and Forward Delete are delete; space, Enter and Done are space and enter; every other key
     * (letters, digits, punctuation, arrows, Esc, Tab, F-keys) is a letter. Pure; no allocation.
     */
    @JvmStatic
    fun classOf(code: Int): HapticClass = when (code) {
        Keyboard.KEYCODE_SHIFT, LatinKeyboardView.KEYCODE_CAPS_LOCK, LatinKeyboardView.KEYCODE_CTRL_LEFT,
        LatinKeyboardView.KEYCODE_ALT_LEFT, LatinKeyboardView.KEYCODE_META_LEFT, LatinKeyboardView.KEYCODE_FN,
        Keyboard.KEYCODE_MODE_CHANGE, Keyboard.KEYCODE_ALT_SYM,
        -> HapticClass.MODIFIER
        Keyboard.KEYCODE_DELETE, LatinKeyboardView.KEYCODE_FORWARD_DEL, ASCII_BACKSPACE -> HapticClass.DELETE
        ASCII_SPACE, ASCII_ENTER, Keyboard.KEYCODE_DONE -> HapticClass.SPACE_ENTER
        else -> HapticClass.LETTER
    }

    /**
     * The strength an install that had Hacker's Keyboard's settings starts with: "Vibrate on keypress" off (or never
     * set) is [VibrationStrength.OFF]; on, its "Vibrate duration" picks the level: up to 20 ms light, up to 60 ms
     * medium (the old default was 40 ms), longer strong.
     */
    @JvmStatic
    fun migrated(vibrateOn: Boolean?, lengthMs: Int?): VibrationStrength {
        if (vibrateOn != true) return VibrationStrength.OFF
        val len = lengthMs ?: return VibrationStrength.MEDIUM
        return when {
            len <= 20 -> VibrationStrength.LIGHT
            len <= 60 -> VibrationStrength.MEDIUM
            else -> VibrationStrength.STRONG
        }
    }

    /**
     * Android 10 and later: the predefined effect for [cls] at [strength], null for [VibrationStrength.OFF]. A letter
     * steps tick, click, heavy click with the strength; space and enter sit a step heavier, delete a step lighter, and
     * a modifier is always a double click, the feel of a latch. Four effects cannot give four classes three steps
     * each, so at Light a letter and delete are both a tick, and at Strong a letter and space are both a heavy click.
     */
    @JvmStatic
    fun predefined(cls: HapticClass, strength: VibrationStrength): HapticEffect? = when (strength) {
        VibrationStrength.OFF -> null
        VibrationStrength.LIGHT -> when (cls) {
            HapticClass.LETTER, HapticClass.DELETE -> HapticEffect.TICK
            HapticClass.SPACE_ENTER -> HapticEffect.CLICK
            HapticClass.MODIFIER -> HapticEffect.DOUBLE_CLICK
        }
        VibrationStrength.MEDIUM -> when (cls) {
            HapticClass.LETTER -> HapticEffect.CLICK
            HapticClass.DELETE -> HapticEffect.TICK
            HapticClass.SPACE_ENTER -> HapticEffect.HEAVY_CLICK
            HapticClass.MODIFIER -> HapticEffect.DOUBLE_CLICK
        }
        VibrationStrength.STRONG -> when (cls) {
            HapticClass.LETTER, HapticClass.SPACE_ENTER -> HapticEffect.HEAVY_CLICK
            HapticClass.DELETE -> HapticEffect.CLICK
            HapticClass.MODIFIER -> HapticEffect.DOUBLE_CLICK
        }
    }

    /**
     * Android 8 and 9: the buzz for [cls] at [strength], null for [VibrationStrength.OFF]. A letter is one 14 ms pulse,
     * space and enter a longer, firmer 22 ms, delete a short, soft 9 ms, and a modifier two 8 ms pulses 45 ms apart.
     * The strength lengthens the pulses (x0.75, x1, x1.35) and, where the vibrator can vary it ([amplitudeControl]),
     * sets their amplitude; where it cannot, the length alone carries the strength.
     */
    @JvmStatic
    fun waveform(cls: HapticClass, strength: VibrationStrength, amplitudeControl: Boolean): Waveform? {
        if (strength == VibrationStrength.OFF) return null
        val stretch = when (strength) {
            VibrationStrength.LIGHT -> 0.75
            VibrationStrength.STRONG -> 1.35
            else -> 1.0
        }
        val (pulseMs, factor) = when (cls) {
            HapticClass.LETTER -> 14L to 1.0
            HapticClass.SPACE_ENTER -> 22L to 1.25
            HapticClass.DELETE -> 9L to 0.8
            HapticClass.MODIFIER -> 8L to 0.9
        }
        val on = (pulseMs * stretch).toLong().coerceAtLeast(1L)
        val amp = if (amplitudeControl) amplitude(strength.percent, factor) else DEFAULT_AMPLITUDE
        return if (cls == HapticClass.MODIFIER) {
            Waveform(longArrayOf(0L, on, MODIFIER_GAP_MS, on), intArrayOf(0, amp, 0, amp))
        } else {
            Waveform(longArrayOf(0L, on), intArrayOf(0, amp))
        }
    }

    /** FlorisBoard's rule: [percent] of full strength times [factor], as an amplitude from 1 to 255. */
    @JvmStatic
    fun amplitude(percent: Int, factor: Double = 1.0): Int = (255.0 * ((percent * factor) / 100.0)).toInt().coerceIn(1, 255)

    /** The view feedback a class gets where the keyboard follows Android's touch feedback setting. */
    @JvmStatic
    fun viewHaptic(cls: HapticClass): ViewHaptic = when (cls) {
        HapticClass.LETTER -> ViewHaptic.KEYBOARD_TAP
        HapticClass.MODIFIER -> ViewHaptic.CONTEXT_CLICK
        HapticClass.DELETE -> ViewHaptic.CLOCK_TICK
        HapticClass.SPACE_ENTER -> ViewHaptic.VIRTUAL_KEY
    }

    private const val MODIFIER_GAP_MS = 45L
    private const val ASCII_BACKSPACE = 8
    private const val ASCII_ENTER = 10
    private const val ASCII_SPACE = 32
}
