package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Haptics per key class and the Vibration strength setting (Droidtop/tracker#903 H12a). */
class KeyHapticsTest {
    @Test fun eachKeyCodeHasItsClass() {
        // Letters, digits, punctuation and the other keys.
        for (code in intArrayOf('a'.code, 'Z'.code, '5'.code, '.'.code, 9, 27, -19, -111, -131)) {
            assertEquals("code $code", HapticClass.LETTER, KeyHaptics.classOf(code))
        }
        // Shift, Caps Lock, Ctrl, Alt, Meta, Fn, the symbols switch.
        for (code in intArrayOf(-1, -115, -113, -57, -117, -119, -2)) {
            assertEquals("code $code", HapticClass.MODIFIER, KeyHaptics.classOf(code))
        }
        // Backspace, Forward Delete, a bare ASCII backspace.
        for (code in intArrayOf(-5, -112, 8)) assertEquals("code $code", HapticClass.DELETE, KeyHaptics.classOf(code))
        // Space, Enter, Done.
        for (code in intArrayOf(32, 10, -4)) assertEquals("code $code", HapticClass.SPACE_ENTER, KeyHaptics.classOf(code))
    }

    @Test fun theStrengthIsReadByIdAndAnythingElseIsOff() {
        assertEquals(VibrationStrength.LIGHT, VibrationStrength.of("light"))
        assertEquals(VibrationStrength.MEDIUM, VibrationStrength.of("medium"))
        assertEquals(VibrationStrength.STRONG, VibrationStrength.of("strong"))
        assertEquals(VibrationStrength.OFF, VibrationStrength.of("off"))
        assertEquals(VibrationStrength.OFF, VibrationStrength.of(null))
        assertEquals(VibrationStrength.OFF, VibrationStrength.of("loud"))
    }

    @Test fun vibrateOnKeypressMigratesToAStrengthByItsDuration() {
        assertEquals(VibrationStrength.OFF, KeyHaptics.migrated(false, 40))
        assertEquals(VibrationStrength.OFF, KeyHaptics.migrated(null, 200))
        // On with Hacker's default of 40 ms, or no duration stored: medium.
        assertEquals(VibrationStrength.MEDIUM, KeyHaptics.migrated(true, 40))
        assertEquals(VibrationStrength.MEDIUM, KeyHaptics.migrated(true, null))
        assertEquals(VibrationStrength.LIGHT, KeyHaptics.migrated(true, 5))
        assertEquals(VibrationStrength.LIGHT, KeyHaptics.migrated(true, 20))
        assertEquals(VibrationStrength.MEDIUM, KeyHaptics.migrated(true, 60))
        assertEquals(VibrationStrength.STRONG, KeyHaptics.migrated(true, 61))
        assertEquals(VibrationStrength.STRONG, KeyHaptics.migrated(true, 200))
    }

    @Test fun offPlaysNothingOnAnyAndroid() {
        for (cls in HapticClass.entries) {
            assertNull(KeyHaptics.predefined(cls, VibrationStrength.OFF))
            assertNull(KeyHaptics.waveform(cls, VibrationStrength.OFF, amplitudeControl = true))
        }
    }

    @Test fun predefinedEffectsStepWithTheStrengthAndAModifierIsADoubleClick() {
        assertEquals(HapticEffect.TICK, KeyHaptics.predefined(HapticClass.LETTER, VibrationStrength.LIGHT))
        assertEquals(HapticEffect.CLICK, KeyHaptics.predefined(HapticClass.LETTER, VibrationStrength.MEDIUM))
        assertEquals(HapticEffect.HEAVY_CLICK, KeyHaptics.predefined(HapticClass.LETTER, VibrationStrength.STRONG))
        for (s in listOf(VibrationStrength.LIGHT, VibrationStrength.MEDIUM, VibrationStrength.STRONG)) {
            assertEquals(HapticEffect.DOUBLE_CLICK, KeyHaptics.predefined(HapticClass.MODIFIER, s))
        }
        // At Medium every class is distinct.
        val medium = HapticClass.entries.map { KeyHaptics.predefined(it, VibrationStrength.MEDIUM) }
        assertEquals(HapticClass.entries.size, medium.toSet().size)
    }

    @Test fun belowAndroid10EachClassIsADistinctBuzzAtEveryStrength() {
        for (s in listOf(VibrationStrength.LIGHT, VibrationStrength.MEDIUM, VibrationStrength.STRONG)) {
            for (amp in listOf(true, false)) {
                val shapes = HapticClass.entries.map { cls ->
                    val w = KeyHaptics.waveform(cls, s, amp)!!
                    w.timings.toList() to w.amplitudes.toList()
                }
                assertEquals("strength $s, amplitude control $amp", HapticClass.entries.size, shapes.toSet().size)
            }
        }
        val modifier = KeyHaptics.waveform(HapticClass.MODIFIER, VibrationStrength.MEDIUM, true)!!
        assertEquals(4, modifier.timings.size)
        assertTrue(!modifier.isOneShot)
        assertTrue(KeyHaptics.waveform(HapticClass.LETTER, VibrationStrength.MEDIUM, true)!!.isOneShot)
    }

    @Test fun aStrongerSettingIsLongerAndFirmer() {
        val light = KeyHaptics.waveform(HapticClass.LETTER, VibrationStrength.LIGHT, true)!!
        val medium = KeyHaptics.waveform(HapticClass.LETTER, VibrationStrength.MEDIUM, true)!!
        val strong = KeyHaptics.waveform(HapticClass.LETTER, VibrationStrength.STRONG, true)!!
        assertTrue(light.timings[1] < medium.timings[1] && medium.timings[1] < strong.timings[1])
        assertTrue(light.amplitudes[1] < medium.amplitudes[1] && medium.amplitudes[1] < strong.amplitudes[1])
        // A vibrator that cannot vary its amplitude gets its own; the length carries the strength.
        val fixed = KeyHaptics.waveform(HapticClass.LETTER, VibrationStrength.STRONG, false)!!
        assertEquals(KeyHaptics.DEFAULT_AMPLITUDE, fixed.amplitudes[1])
        assertEquals(strong.timings[1], fixed.timings[1])
    }

    @Test fun amplitudeFollowsFlorisBoardsRule() {
        assertEquals(255, KeyHaptics.amplitude(100))
        assertEquals(165, KeyHaptics.amplitude(65))
        assertEquals(255, KeyHaptics.amplitude(100, 1.25))
        assertEquals(1, KeyHaptics.amplitude(0))
        assertEquals(89, KeyHaptics.amplitude(35))
    }

    @Test fun theEmbeddedKeyboardGivesEachClassItsOwnViewFeedback() {
        val constants = HapticClass.entries.map { KeyHaptics.viewHaptic(it) }
        assertEquals(HapticClass.entries.size, constants.toSet().size)
        assertEquals(ViewHaptic.KEYBOARD_TAP, KeyHaptics.viewHaptic(HapticClass.LETTER))
        assertNotEquals(KeyHaptics.viewHaptic(HapticClass.LETTER), KeyHaptics.viewHaptic(HapticClass.DELETE))
    }
}
