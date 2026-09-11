package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real `ThemeData::setThemeTransitions` (ThemeData.cpp:1042-1120) and the
 * capabilities parse behind it (:1568-1700).
 */
class EsDeTransitionsTest {

    private val instantFade = EsDeTransitionProfile(
        name = "instantfade",
        animations = mapOf(
            EsDeViewTransition.SYSTEM_TO_SYSTEM to EsDeTransitionAnimation.INSTANT,
            EsDeViewTransition.SYSTEM_TO_GAMELIST to EsDeTransitionAnimation.FADE,
            EsDeViewTransition.GAMELIST_TO_GAMELIST to EsDeTransitionAnimation.INSTANT,
            EsDeViewTransition.GAMELIST_TO_SYSTEM to EsDeTransitionAnimation.FADE,
            EsDeViewTransition.STARTUP_TO_SYSTEM to EsDeTransitionAnimation.SLIDE,
            EsDeViewTransition.STARTUP_TO_GAMELIST to EsDeTransitionAnimation.SLIDE,
        ),
    )
    private val instant = EsDeTransitionProfile(
        name = "instant",
        animations = EsDeViewTransition.entries.associateWith { EsDeTransitionAnimation.INSTANT },
    )

    @Test
    fun `a theme with no profile at all cuts between every view`() {
        val animations = esDeTransitionAnimations(emptyList())
        assertEquals(6, animations.size)
        assertEquals(
            setOf(EsDeTransitionAnimation.INSTANT),
            animations.values.toSet(),
        )
    }

    @Test
    fun `automatic takes the theme's FIRST declared profile`() {
        // decaffe's own pair, in its own order: instantfade first.
        val animations = esDeTransitionAnimations(listOf(instantFade, instant))
        assertEquals(EsDeTransitionAnimation.FADE, animations[EsDeViewTransition.SYSTEM_TO_GAMELIST])
        assertEquals(EsDeTransitionAnimation.INSTANT, animations[EsDeViewTransition.SYSTEM_TO_SYSTEM])
        assertEquals(EsDeTransitionAnimation.SLIDE, animations[EsDeViewTransition.STARTUP_TO_SYSTEM])
    }

    @Test
    fun `a variant naming a profile beats the first-declared rule`() {
        val animations = esDeTransitionAnimations(
            listOf(instantFade, instant),
            variantDefinedTransitions = "instant",
        )
        assertEquals(EsDeTransitionAnimation.INSTANT, animations[EsDeViewTransition.SYSTEM_TO_GAMELIST])
    }

    @Test
    fun `a named setting picks that profile, and a theme profile beats a builtin`() {
        assertEquals(
            EsDeTransitionAnimation.INSTANT,
            esDeTransitionAnimations(listOf(instantFade, instant), setting = "instant")[
                EsDeViewTransition.SYSTEM_TO_GAMELIST,
            ],
        )
        // builtin-slide only means anything when no profile has that name.
        val slid = esDeTransitionAnimations(listOf(instantFade), setting = "builtin-slide")
        assertEquals(setOf(EsDeTransitionAnimation.SLIDE), slid.values.toSet())
    }

    @Test
    fun `a theme may suppress a builtin profile, which then falls back to instant`() {
        val suppressed = esDeTransitionAnimations(
            listOf(instantFade),
            setting = "builtin-fade",
            suppressedProfiles = listOf("builtin-fade"),
        )
        assertEquals(setOf(EsDeTransitionAnimation.INSTANT), suppressed.values.toSet())
    }

    @Test
    fun `a profile only overrides the transitions it names`() {
        val partial = EsDeTransitionProfile(
            "partial",
            animations = mapOf(EsDeViewTransition.SYSTEM_TO_GAMELIST to EsDeTransitionAnimation.SLIDE),
        )
        val animations = esDeTransitionAnimations(listOf(partial))
        assertEquals(EsDeTransitionAnimation.SLIDE, animations[EsDeViewTransition.SYSTEM_TO_GAMELIST])
        assertEquals(EsDeTransitionAnimation.INSTANT, animations[EsDeViewTransition.GAMELIST_TO_SYSTEM])
    }

    // The capabilities PARSE itself is not unit tested here on purpose:
    // EsDeThemeParser goes through android.util.Xml, which a plain JVM
    // test cannot construct. What is testable without a device -- and
    // what the C++ actually makes subtle -- is the selection above.
}
