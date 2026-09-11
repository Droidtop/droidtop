package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EsDeStationaryTest {
    private val slide = EsDeTransitionAnimation.SLIDE
    private val fade = EsDeTransitionAnimation.FADE

    @Test
    fun `the default mode is never, and an unknown value keeps it`() {
        assertEquals(EsDeStationaryMode.NEVER, EsDeStationaryMode.parse(null))
        assertEquals(EsDeStationaryMode.NEVER, EsDeStationaryMode.parse("sometimes"))
        assertEquals(EsDeStationaryMode.ALWAYS, EsDeStationaryMode.parse("always"))
        assertEquals(EsDeStationaryMode.WITHIN_VIEW, EsDeStationaryMode.parse("withinView"))
        assertEquals(EsDeStationaryMode.BETWEEN_VIEWS, EsDeStationaryMode.parse("betweenViews"))
    }

    @Test
    fun `nothing is stationary unless the transition is a slide`() {
        for (kind in EsDeViewTransition.entries) {
            assertFalse(EsDeTransitionBehaviour.isStationary(EsDeStationaryMode.ALWAYS, kind, fade))
            assertFalse(
                EsDeTransitionBehaviour.isStationary(
                    EsDeStationaryMode.ALWAYS,
                    kind,
                    EsDeTransitionAnimation.INSTANT,
                ),
            )
        }
    }

    @Test
    fun `withinView covers the same-view slides and betweenViews the others`() {
        val within = EsDeStationaryMode.WITHIN_VIEW
        val between = EsDeStationaryMode.BETWEEN_VIEWS
        assertTrue(EsDeTransitionBehaviour.isStationary(within, EsDeViewTransition.SYSTEM_TO_SYSTEM, slide))
        assertTrue(EsDeTransitionBehaviour.isStationary(within, EsDeViewTransition.GAMELIST_TO_GAMELIST, slide))
        assertFalse(EsDeTransitionBehaviour.isStationary(within, EsDeViewTransition.SYSTEM_TO_GAMELIST, slide))
        assertTrue(EsDeTransitionBehaviour.isStationary(between, EsDeViewTransition.GAMELIST_TO_SYSTEM, slide))
        assertFalse(EsDeTransitionBehaviour.isStationary(between, EsDeViewTransition.SYSTEM_TO_SYSTEM, slide))
    }

    @Test
    fun `always covers both, never covers neither`() {
        for (kind in listOf(EsDeViewTransition.SYSTEM_TO_SYSTEM, EsDeViewTransition.SYSTEM_TO_GAMELIST)) {
            assertTrue(EsDeTransitionBehaviour.isStationary(EsDeStationaryMode.ALWAYS, kind, slide))
            assertFalse(EsDeTransitionBehaviour.isStationary(EsDeStationaryMode.NEVER, kind, slide))
        }
    }

    @Test
    fun `the startup transitions hold nothing stationary`() {
        assertFalse(
            EsDeTransitionBehaviour.isStationary(
                EsDeStationaryMode.ALWAYS,
                EsDeViewTransition.STARTUP_TO_GAMELIST,
                slide,
            ),
        )
    }

    @Test
    fun `an element only opts out of a slide between two different views`() {
        assertTrue(EsDeTransitionBehaviour.rendersDuringTransition(true, EsDeViewTransition.SYSTEM_TO_GAMELIST, slide))
        assertFalse(EsDeTransitionBehaviour.rendersDuringTransition(false, EsDeViewTransition.SYSTEM_TO_GAMELIST, slide))
        assertTrue(EsDeTransitionBehaviour.rendersDuringTransition(false, EsDeViewTransition.SYSTEM_TO_GAMELIST, fade))
        assertTrue(EsDeTransitionBehaviour.rendersDuringTransition(false, EsDeViewTransition.GAMELIST_TO_GAMELIST, slide))
    }
}
