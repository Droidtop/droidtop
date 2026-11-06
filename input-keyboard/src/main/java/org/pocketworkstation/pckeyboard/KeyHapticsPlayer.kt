package org.pocketworkstation.pckeyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Plays a key press's haptic in the input method (Droidtop/tracker#903 H12a; docs/SPEC.md 6a, "Motion and haptics"):
 * the effect [KeyHaptics] gives the key's class at the "Vibration strength" chosen. Each class's effect is built once
 * when the strength is set, never per press, ported from Unexpected Keyboard's `VibratorCompat`
 * (https://github.com/Julow/Unexpected-Keyboard at 0695efdc, GPL-3.0), which keeps its predefined tick the same way: a
 * predefined effect on Android 10 and later ([PredefinedHaptics], a class of its own because `createPredefined` is
 * newer than the keyboard's Android 8 minimum), a one-shot or a two-pulse waveform on Android 8 and 9. "Custom" is
 * Hacker's Keyboard's own behaviour, kept: every key a one-shot of the "Vibrate duration", on every Android.
 *
 * The keyboard droidtop embeds has no vibration setting of its own and does not use this: it follows Android's touch
 * feedback through [viewFeedback], as droidtop's text-entry slab does.
 */
class KeyHapticsPlayer(context: Context) {
    private val vibrator: Vibrator? =
        (context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.takeIf { it.hasVibrator() }
    private val effects = arrayOfNulls<VibrationEffect>(HapticClass.entries.size)
    private var built = false

    /** The strength last set; [VibrationStrength.OFF] plays nothing. */
    var strength: VibrationStrength = VibrationStrength.OFF
        private set

    /** The "Vibrate duration" [VibrationStrength.CUSTOM] plays, in ms. */
    var customMs: Long = KeyHaptics.DEFAULT_LEN_MS.toLong()
        private set

    /** Sets the strength and, for [VibrationStrength.CUSTOM], the stored "Vibrate duration" [lengthMs]. */
    fun setStrength(value: VibrationStrength, lengthMs: Int) {
        val length = KeyHaptics.customLengthMs(lengthMs)
        if (built && value == strength && length == customMs) return
        strength = value
        customMs = length
        built = true
        val amplitudeControl = vibrator?.hasAmplitudeControl() == true
        for (cls in HapticClass.entries) effects[cls.ordinal] = if (vibrator == null) null else build(cls, value, amplitudeControl)
    }

    /** Reads both settings from [prefs]. */
    fun load(prefs: SharedPreferences) {
        setStrength(VibrationStrength.of(prefs.getString(KeyHaptics.PREF, null)), storedLength(prefs))
    }

    /** The haptic for a press of the Hacker's Keyboard key [code]. */
    fun play(code: Int) {
        val v = vibrator ?: return
        val effect = effects[KeyHaptics.classOf(code).ordinal] ?: return
        runCatching { v.vibrate(effect) }
    }

    private fun build(cls: HapticClass, value: VibrationStrength, amplitudeControl: Boolean): VibrationEffect? {
        if (value == VibrationStrength.CUSTOM) {
            return runCatching { VibrationEffect.createOneShot(customMs, VibrationEffect.DEFAULT_AMPLITUDE) }.getOrNull()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val effect = KeyHaptics.predefined(cls, value) ?: return null
            return PredefinedHaptics.effect(effect)
        }
        val wave = KeyHaptics.waveform(cls, value, amplitudeControl) ?: return null
        return runCatching {
            if (wave.isOneShot) {
                VibrationEffect.createOneShot(wave.timings[1], wave.amplitudes[1])
            } else {
                VibrationEffect.createWaveform(wave.timings, wave.amplitudes, -1)
            }
        }.getOrNull()
    }

    companion object {
        /**
         * Moves Hacker's Keyboard's "Vibrate on keypress" into "Vibration strength", once ([KeyHaptics.migrated]), and
         * removes it: nothing else reads it. "Vibrate duration" is left as it is; a duration other than the default
         * makes the strength Custom, which plays it. Nothing happens when the strength is already stored or the
         * old switch was never stored. `apply` writes the file off the main thread.
         */
        @JvmStatic
        fun migrate(prefs: SharedPreferences) {
            if (prefs.contains(KeyHaptics.PREF)) return
            if (!prefs.contains(KeyHaptics.OLD_ON)) return
            val on = runCatching { prefs.getBoolean(KeyHaptics.OLD_ON, false) }.getOrDefault(false)
            val length = if (prefs.contains(KeyHaptics.LEN_PREF)) storedLength(prefs) else null
            prefs.edit()
                .putString(KeyHaptics.PREF, KeyHaptics.migrated(on, length).id)
                .remove(KeyHaptics.OLD_ON)
                .apply()
        }

        /** The stored "Vibrate duration" in ms (Hacker's stores it as text, "40"), or the default. */
        @JvmStatic
        fun storedLength(prefs: SharedPreferences): Int {
            val text = runCatching { prefs.getString(KeyHaptics.LEN_PREF, null) }.getOrNull() ?: return KeyHaptics.DEFAULT_LEN_MS
            return LatinIME.getIntFromString(text, KeyHaptics.DEFAULT_LEN_MS)
        }

        /**
         * The press of [code] on a keyboard that follows Android's own touch feedback setting (the keyboard droidtop
         * embeds): `performHapticFeedback` with the class's constant, which the system's setting governs.
         */
        @JvmStatic
        fun viewFeedback(view: View, code: Int) {
            val constant = when (KeyHaptics.viewHaptic(KeyHaptics.classOf(code))) {
                ViewHaptic.KEYBOARD_TAP -> HapticFeedbackConstants.KEYBOARD_TAP
                ViewHaptic.CONTEXT_CLICK -> HapticFeedbackConstants.CONTEXT_CLICK
                ViewHaptic.CLOCK_TICK -> HapticFeedbackConstants.CLOCK_TICK
                ViewHaptic.VIRTUAL_KEY -> HapticFeedbackConstants.VIRTUAL_KEY
            }
            view.performHapticFeedback(constant)
        }
    }
}

/**
 * The predefined effects of Android 10 (API 29). A class of its own, reached only under a version check in
 * [KeyHapticsPlayer], so nothing newer than the keyboard's Android 8 minimum is named in a class that loads below it
 * (build-scripts/check_class_load_api.py).
 */
@RequiresApi(29)
internal object PredefinedHaptics {
    fun effect(effect: HapticEffect): VibrationEffect = VibrationEffect.createPredefined(
        when (effect) {
            HapticEffect.TICK -> VibrationEffect.EFFECT_TICK
            HapticEffect.CLICK -> VibrationEffect.EFFECT_CLICK
            HapticEffect.HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
            HapticEffect.DOUBLE_CLICK -> VibrationEffect.EFFECT_DOUBLE_CLICK
        },
    )
}
