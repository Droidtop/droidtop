package dev.droidtop.library.consoles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A platform owned by the store integration is never inferred from a folder name. */
class CanResolveFromFolderTest {

    private fun def(ownedBy: String?) =
        ConsoleSystemDef(id = "x", displayName = "X", extensions = setOf("x"), retroArchCore = null, ownedBy = ownedBy)

    @Test
    fun `a platform nobody owns resolves from its folder`() {
        assertTrue(def(null).canResolveFromFolder())
    }

    @Test
    fun `a store-owned platform does not, whatever the case`() {
        assertFalse(def("store").canResolveFromFolder())
        assertFalse(def("STORE").canResolveFromFolder())
    }

    @Test
    fun `another owner still resolves from its folder`() {
        assertTrue(def("other").canResolveFromFolder())
    }
}
