package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks for the real `datetime` display port. Every expected value is
 * derived by hand from `DateTimeComponent::getDisplayString()`
 * (DateTimeComponent.cpp:86-135), `Utils::Time::Duration`'s own
 * decomposition (TimeUtil.cpp:73-80) and the `displayRelative` resolution
 * at DateTimeComponent.cpp:368-372 -- not from recorded output.
 */
class EsDeDateTimeTest {

    /** DateTimeComponent.cpp:126-131 -- epoch 0 is "no value", and the default text is "unknown". */
    @Test
    fun `absolute mode with no value shows unknown`() {
        val display = esDeDateTimeDisplay(0L, nowSeconds = 1_700_000_000L, displayRelative = false, defaultValue = null)
        assertEquals(EsDeDateTimeDisplay.Literal("unknown"), display)
    }

    /** Same lines -- a declared defaultValue replaces "unknown" entirely. */
    @Test
    fun `absolute mode with no value prefers the theme's own defaultValue`() {
        val display = esDeDateTimeDisplay(0L, 1_700_000_000L, displayRelative = false, defaultValue = "n/a")
        assertEquals(EsDeDateTimeDisplay.Literal("n/a"), display)
    }

    /** DateTimeComponent.cpp:133 -- a real value is handed on to be strftime-formatted. */
    @Test
    fun `absolute mode with a real value defers to the format string`() {
        val display = esDeDateTimeDisplay(1_000_000L, 1_700_000_000L, displayRelative = false, defaultValue = null)
        assertEquals(EsDeDateTimeDisplay.Formatted(1_000_000L), display)
    }

    /**
     * DateTimeComponent.cpp:92-99 -- the epoch guard. Anything under
     * 60*60*23 = 82800 is "never played", because a stored epoch 0 read
     * back through a local timezone lands somewhere in that window.
     */
    @Test
    fun `relative mode treats anything inside the first 23 hours as never`() {
        assertEquals(82800L, ES_DE_EPOCH_GUARD_SECONDS)
        val justInside = esDeDateTimeDisplay(82_799L, 1_700_000_000L, displayRelative = true, defaultValue = null)
        assertEquals(EsDeDateTimeDisplay.Literal("never"), justInside)
        // One second later it is a real timestamp: now - 82800 = 100
        // seconds, so the seconds branch.
        val justOutside = esDeDateTimeDisplay(82_800L, 82_900L, displayRelative = true, defaultValue = null)
        assertEquals(EsDeDateTimeDisplay.Literal("100 seconds ago"), justOutside)
    }

    /** DateTimeComponent.cpp:94-97 -- defaultValue overrides "never" too. */
    @Test
    fun `relative mode with no value prefers the theme's own defaultValue`() {
        val display = esDeDateTimeDisplay(0L, 1_700_000_000L, displayRelative = true, defaultValue = "not yet")
        assertEquals(EsDeDateTimeDisplay.Literal("not yet"), display)
    }

    /**
     * TimeUtil.cpp:73-80 + DateTimeComponent.cpp:106-122: the coarsest
     * NON-ZERO unit wins, and the units are a decomposition, not a total.
     * 2 days, 3 hours, 4 minutes, 5 seconds = 183_845 s -> "2 days ago".
     */
    @Test
    fun `relative mode picks the coarsest non-zero unit`() {
        val base = 1_000_000L
        val span = 2L * 86400 + 3 * 3600 + 4 * 60 + 5
        assertEquals(183_845L, span)
        assertEquals(
            EsDeDateTimeDisplay.Literal("2 days ago"),
            esDeDateTimeDisplay(base, base + span, displayRelative = true, defaultValue = null),
        )
        // Drop the days: 3h 4m 5s = 11_045 s -> the hours branch.
        assertEquals(
            EsDeDateTimeDisplay.Literal("3 hours ago"),
            esDeDateTimeDisplay(base, base + 11_045L, displayRelative = true, defaultValue = null),
        )
        // Drop the hours: 4m 5s = 245 s -> the minutes branch.
        assertEquals(
            EsDeDateTimeDisplay.Literal("4 minutes ago"),
            esDeDateTimeDisplay(base, base + 245L, displayRelative = true, defaultValue = null),
        )
        // Drop the minutes -> the seconds branch, which is also the
        // else-branch, so a zero span prints there rather than nowhere.
        assertEquals(
            EsDeDateTimeDisplay.Literal("5 seconds ago"),
            esDeDateTimeDisplay(base, base + 5L, displayRelative = true, defaultValue = null),
        )
        assertEquals(
            EsDeDateTimeDisplay.Literal("0 seconds ago"),
            esDeDateTimeDisplay(base, base, displayRelative = true, defaultValue = null),
        )
    }

    /** DateTimeComponent.cpp:106-122 -- ES-DE uses ngettext, so 1 is singular. */
    @Test
    fun `relative mode uses singular units for exactly one`() {
        val base = 1_000_000L
        assertEquals(
            EsDeDateTimeDisplay.Literal("1 day ago"),
            esDeDateTimeDisplay(base, base + 86_400L, displayRelative = true, defaultValue = null),
        )
        assertEquals(
            EsDeDateTimeDisplay.Literal("1 hour ago"),
            esDeDateTimeDisplay(base, base + 3_600L, displayRelative = true, defaultValue = null),
        )
        assertEquals(
            EsDeDateTimeDisplay.Literal("1 minute ago"),
            esDeDateTimeDisplay(base, base + 60L, displayRelative = true, defaultValue = null),
        )
        assertEquals(
            EsDeDateTimeDisplay.Literal("1 second ago"),
            esDeDateTimeDisplay(base, base + 1L, displayRelative = true, defaultValue = null),
        )
    }

    /**
     * droidtop's one documented divergence: ES-DE builds its Duration from
     * an unsigned int and would wrap, this floors at zero.
     */
    @Test
    fun `a time in the future floors at zero rather than wrapping`() {
        val display = esDeDateTimeDisplay(2_000_000L, 1_000_000L, displayRelative = true, defaultValue = null)
        assertEquals(EsDeDateTimeDisplay.Literal("0 seconds ago"), display)
    }

    /** DateTimeComponent.cpp:368-372 -- lastplayed implies relative, and the property overrides in both directions. */
    @Test
    fun `displayRelative defaults on for lastplayed only and is overridable`() {
        assertTrue(esDeDisplayRelative("lastplayed", declared = null))
        assertFalse(esDeDisplayRelative("releasedate", declared = null))
        assertFalse(esDeDisplayRelative(null, declared = null))
        assertFalse(esDeDisplayRelative("lastplayed", declared = false))
        assertTrue(esDeDisplayRelative("releasedate", declared = true))
    }
}
