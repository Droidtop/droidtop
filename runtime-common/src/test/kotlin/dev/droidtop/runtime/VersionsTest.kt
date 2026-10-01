package dev.droidtop.runtime

import dev.droidtop.runtime.util.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun `loose versions compare numeric and text components`() {
        assertTrue(Versions.compareLoose("0.10", "0.9") > 0)
        assertTrue(Versions.compareLoose("1.2", "1.1.9") > 0)
        assertTrue(Versions.compareLoose("0.8.3b", "0.8.3") > 0)
        assertTrue(Versions.compareLoose("1.0", "") > 0)
        assertEquals(0, Versions.compareLoose("1.2", "1.2"))
        assertEquals(0, Versions.compareLoose("1_2", "1-2"))
    }
}
