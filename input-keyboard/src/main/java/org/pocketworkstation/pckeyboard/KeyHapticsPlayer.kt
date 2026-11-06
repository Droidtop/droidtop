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
 * newer than the keyboard's Android 8 minimum), a one-shot or a two-pulse waveform on Android 8 and 9.
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

    fun setStrength(value: VibrationStrength) {
        if (built && value == strength) return
        strength = value
        built = true
        val amplitudeControl = vibrator?.hasAmplitudeControl() == true
        for (cls in HapticClass.entries) effects[cls.ordinal] = if (vibrator == null) null else build(cls, value, amplitudeControl)
    }

    /** The haptic for a press of the Hacker's Keyboard key [code]. */
    fun play(code: Int) {
        val v = vibrator ?: return
        val effect = effects[KeyHaptics.classOf(code).ordinal] ?: return
        runCatching { v.vibrate(effect) }
    }

    private fun build(cls: HapticClass, value: VibrationStrength, amplitudeControl: Boolean): VibrationEffect? {
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
         * Moves Hacker's Keyboard's "Vibrate on keypress" and "Vibrate duration" into "Vibration strength", once
         * ([KeyHaptics.migrated]), and removes them: one setting, not two. Nothing happens when the strength is already
         * stored or neither old setting was ever stored. `apply` writes the file off the main thread.
         */
        @JvmStatic
        fun migrate(prefs: SharedPreferences) {
            if (prefs.contains(KeyHaptics.PREF)) return
            if (!prefs.contains(KeyHaptics.OLD_ON) && !prefs.contains(KeyHaptics.OLD_LEN)) return
            val on = runCatching { prefs.getBoolean(KeyHaptics.OLD_ON, false) }.getOrDefault(false)
            val length = runCatching { prefs.getString(KeyHaptics.OLD_LEN, null) }.getOrNull()
                ?.let { LatinIME.getIntFromString(it, -1) }?.takeIf { it > 0 }
            prefs.edit()
                .putString(KeyHaptics.PREF, KeyHaptics.migrated(on, length).id)
                .remove(KeyHaptics.OLD_ON)
                .remove(KeyHaptics.OLD_LEN)
                .apply()
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
