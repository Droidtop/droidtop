package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceSizeTest {
    @Test
    fun `Auto and anything not offered are the canvas as reported`() {
        assertEquals(1f, InterfaceSize.factorOf(InterfaceSize.AUTO), 0f)
        assertEquals(1f, InterfaceSize.factorOf(null), 0f)
        assertEquals(1f, InterfaceSize.factorOf("300"), 0f)
        assertEquals(0.75f, InterfaceSize.factorOf("75"), 0f)
        assertEquals(1.5f, InterfaceSize.factorOf("150"), 0f)
    }

    @Test
    fun `the main screen is the second one when present and chosen, else the built-in one`() {
        val second = 2
        assertEquals(DisplayRole.MAIN, InterfaceSize.roleOf(second, second, MainScreenChoice.SECOND_WHEN_PRESENT))
        assertEquals(DisplayRole.COMPANION, InterfaceSize.roleOf(0, second, MainScreenChoice.SECOND_WHEN_PRESENT))
        assertEquals(DisplayRole.MAIN, InterfaceSize.roleOf(0, second, MainScreenChoice.BUILT_IN))
        assertEquals(DisplayRole.COMPANION, InterfaceSize.roleOf(second, second, MainScreenChoice.BUILT_IN))
        // One screen: it is the main one whatever the choice.
        assertEquals(DisplayRole.MAIN, InterfaceSize.roleOf(0, null, MainScreenChoice.SECOND_WHEN_PRESENT))
    }
}
