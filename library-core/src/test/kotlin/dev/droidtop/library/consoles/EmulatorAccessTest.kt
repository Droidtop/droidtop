package dev.droidtop.library.consoles

import android.content.pm.PermissionInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorAccessTest {
    @Test
    fun `only a dangerous base level is a runtime permission`() {
        assertTrue(EmulatorAccess.isRuntime(PermissionInfo.PROTECTION_DANGEROUS))
        // A runtime permission can carry flags above the base level (for example "pre23").
        assertTrue(EmulatorAccess.isRuntime(PermissionInfo.PROTECTION_DANGEROUS or 0x80))
        assertFalse(EmulatorAccess.isRuntime(PermissionInfo.PROTECTION_NORMAL))
        assertFalse(EmulatorAccess.isRuntime(PermissionInfo.PROTECTION_SIGNATURE))
    }
}
