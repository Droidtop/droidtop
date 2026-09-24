package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainScreenTest {
    @Test
    fun `flipping twice returns to the original choice`() {
        MainScreenChoice.entries.forEach { assertEquals(it, it.flipped().flipped()) }
        assertEquals(MainScreenChoice.BUILT_IN, MainScreenChoice.SECOND_WHEN_PRESENT.flipped())
    }

    @Test
    fun `a legacy assignment with the built-in panel upper migrates to BUILT_IN`() {
        val legacy = mapOf("0" to "UPPER_OUTPUT", "9" to "LOWER_INPUT")
        assertEquals(MainScreenChoice.BUILT_IN, MainScreen.fromLegacyAssignment(legacy))
    }

    @Test
    fun `a legacy assignment with the add-on upper migrates to SECOND_WHEN_PRESENT`() {
        // The add-on's id is whatever it enumerated as then; any non-zero id
        // is the second screen, which is exactly why the id is not kept.
        val legacy = mapOf("0" to "LOWER_INPUT", "12" to "UPPER_OUTPUT")
        assertEquals(MainScreenChoice.SECOND_WHEN_PRESENT, MainScreen.fromLegacyAssignment(legacy))
    }

    @Test
    fun `nothing usable in the legacy store migrates nothing`() {
        assertNull(MainScreen.fromLegacyAssignment(emptyMap()))
        assertNull(MainScreen.fromLegacyAssignment(mapOf("0" to "garbage")))
    }
}
