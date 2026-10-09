package dev.droidtop.shell.gamepad.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The interface-sound roles, where each comes from, and the 50 ms throttle (docs/SPEC.md "Interface sounds"). */
class UiSoundsTest {

    private val theme = ES_DE_NAVIGATION_SOUND_NAMES.toSet()
    private val pack = UiSound.entries.map { it.packFile }.toSet()

    @Test
    fun `a theme's sound overrides the bundled one, role by role`() {
        assertEquals(UiSoundSource.Theme("scroll"), uiSoundSource(UiSound.MOVE, theme, pack))
        assertEquals(UiSoundSource.Theme("back"), uiSoundSource(UiSound.PANEL_CLOSE, theme, pack))
        // ES-DE has no bump or modal sound: the bundled set's.
        assertEquals(UiSoundSource.Pack("bump"), uiSoundSource(UiSound.BUMP, theme, pack))
        assertEquals(UiSoundSource.Pack("modal_show"), uiSoundSource(UiSound.MODAL_SHOW, theme, pack))
        // A theme that declares only some sounds: the rest come from the bundled set.
        assertEquals(UiSoundSource.Pack("launch"), uiSoundSource(UiSound.LAUNCH, setOf("scroll"), pack))
    }

    @Test
    fun `a role neither supplies plays nothing`() {
        assertEquals(UiSoundSource.None, uiSoundSource(UiSound.TOAST, theme, emptySet()))
        assertEquals(UiSoundSource.None, uiSoundSource(UiSound.MOVE, emptySet(), emptySet()))
    }

    @Test
    fun `every role names one of ES-DE's seven sounds or none, and has its own bundled file`() {
        UiSound.entries.forEach { sound -> sound.themeName?.let { assertTrue(it in ES_DE_NAVIGATION_SOUND_NAMES) } }
        assertEquals(UiSound.entries.size, pack.size)
    }

    @Test
    fun `at most one cue per 50 ms, and launch is never held back`() {
        val throttle = CueThrottle()
        assertTrue(throttle.allow(UiSound.MOVE, 1_000))
        assertFalse(throttle.allow(UiSound.MOVE, 1_030))
        assertFalse(throttle.allow(UiSound.CONFIRM, 1_049))
        assertTrue(throttle.allow(UiSound.MOVE, 1_060))
        assertTrue(throttle.allow(UiSound.LAUNCH, 1_061))
    }

    @Test
    fun `only direction cues are held back from touch`() {
        assertTrue(UiSound.MOVE.move && UiSound.BUMP.move && UiSound.SLIDER.move && UiSound.TAB.move)
        assertFalse(UiSound.CONFIRM.move || UiSound.PANEL_OPEN.move || UiSound.MODAL_SHOW.move || UiSound.TOAST.move)
    }
}
