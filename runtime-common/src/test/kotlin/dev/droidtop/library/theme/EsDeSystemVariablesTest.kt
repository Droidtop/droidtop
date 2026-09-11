package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SystemData.cpp:1978-2032: exactly one of the three collection-suffixed
 * families carries a value; the other two carry the backspace flag that
 * ThemeData.cpp:2249-2256 skips the property on.
 */
class EsDeSystemVariablesTest {
    private val flag = EsDeSystemVariables.NOT_APPLICABLE

    @Test
    fun `a regular system fills only the noCollections family`() {
        val vars = EsDeSystemVariables.forSystem("Nintendo 3DS", "n3ds", EsDeCollectionKind.NONE)
        assertEquals("Nintendo 3DS", vars["system.fullName"])
        assertEquals("Nintendo 3DS", vars["system.fullName.noCollections"])
        assertEquals("n3ds", vars["system.theme.noCollections"])
        assertEquals(flag, vars["system.fullName.autoCollections"])
        assertEquals(flag, vars["system.fullName.customCollections"])
        assertEquals(flag, vars["system.theme.customCollections"])
    }

    @Test
    fun `an automatic collection fills only the autoCollections family`() {
        val vars = EsDeSystemVariables.forSystem("All games", "auto-allgames", EsDeCollectionKind.AUTO)
        assertEquals("All games", vars["system.name.autoCollections"])
        assertEquals("auto-allgames", vars["system.theme.autoCollections"])
        assertEquals(flag, vars["system.fullName.noCollections"])
        assertEquals(flag, vars["system.fullName.customCollections"])
    }

    @Test
    fun `a custom collection fills only the customCollections family`() {
        val vars = EsDeSystemVariables.forSystem("Shmups", "custom-collections", EsDeCollectionKind.CUSTOM)
        assertEquals("Shmups", vars["system.fullName.customCollections"])
        assertEquals("custom-collections", vars["system.theme.customCollections"])
        assertEquals(flag, vars["system.fullName.autoCollections"])
        assertEquals(flag, vars["system.name.noCollections"])
    }

    @Test
    fun `the flag is one backspace character`() {
        assertEquals(1, flag.length)
        assertEquals(8, flag[0].code)
    }

    @Test
    fun `a system with no name at all still flags every suffixed variable`() {
        val vars = EsDeSystemVariables.forSystem(null, null, EsDeCollectionKind.NONE)
        assertEquals(null, vars["system.fullName"])
        assertEquals(flag, vars["system.fullName.noCollections"])
        assertEquals(flag, vars["system.theme.autoCollections"])
    }
}
