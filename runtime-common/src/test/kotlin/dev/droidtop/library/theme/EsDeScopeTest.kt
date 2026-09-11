package dev.droidtop.library.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** See [esDeScopeAllows] for the real source of every case here. */
class EsDeScopeTest {
    private fun element(scope: String?) = EsDeThemeElement(
        type = "clock",
        key = "clock_test",
        properties = if (scope == null) emptyMap() else mapOf("scope" to EsDeThemeValue.Str(scope)),
    )

    @Test
    fun `the real default is shared, drawn in both states`() {
        assertTrue(esDeScopeAllows(element(null), menuOpen = false))
        assertTrue(esDeScopeAllows(element(null), menuOpen = true))
        assertTrue(esDeScopeAllows(element("shared"), menuOpen = false))
        assertTrue(esDeScopeAllows(element("shared"), menuOpen = true))
    }

    @Test
    fun `view hides while a menu is open and menu hides while none is`() {
        assertTrue(esDeScopeAllows(element("view"), menuOpen = false))
        assertFalse(esDeScopeAllows(element("view"), menuOpen = true))
        assertFalse(esDeScopeAllows(element("menu"), menuOpen = false))
        assertTrue(esDeScopeAllows(element("menu"), menuOpen = true))
    }

    @Test
    fun `none never draws and an invalid value falls back to shared`() {
        assertFalse(esDeScopeAllows(element("none"), menuOpen = false))
        assertFalse(esDeScopeAllows(element("none"), menuOpen = true))
        assertTrue(esDeScopeAllows(element("everywhere"), menuOpen = false))
        assertTrue(esDeScopeAllows(element("everywhere"), menuOpen = true))
    }
}
