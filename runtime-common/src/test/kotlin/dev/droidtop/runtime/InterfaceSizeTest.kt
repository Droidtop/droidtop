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
    fun `the main screen is the one droidtop's menus are on, every other is the companion's`() {
        assertEquals(DisplayRole.MAIN, InterfaceSize.roleOf(2, mainId = 2))
        assertEquals(DisplayRole.COMPANION, InterfaceSize.roleOf(0, mainId = 2))
        assertEquals(DisplayRole.MAIN, InterfaceSize.roleOf(0, mainId = 0))
        assertEquals(DisplayRole.COMPANION, InterfaceSize.roleOf(3, mainId = 0))
    }
}
