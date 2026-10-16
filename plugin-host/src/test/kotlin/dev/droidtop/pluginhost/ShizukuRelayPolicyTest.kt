package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Shizuku relay forwards only the calls a declared full-access point prescribes (docs/plugin-api.md 5.6). */
class ShizukuRelayPolicyTest {
    private val code = ShizukuRelayPolicy::code

    @Test
    fun `state reads, permission and attaching are forwarded for any plugin`() {
        listOf(2, 3, 4, 8, 14, 15, 16, 17).forEach { assertNull("id $it", ShizukuRelayPolicy.codeRefusal(code(it), emptySet())) }
    }

    @Test
    fun `starting a process needs a declared command point`() {
        assertNotNull(ShizukuRelayPolicy.codeRefusal(code(7), emptySet()))
        assertNotNull(ShizukuRelayPolicy.codeRefusal(code(7), setOf("source.search")))
        assertNull(ShizukuRelayPolicy.codeRefusal(code(7), setOf("priv.shell")))
        assertNull(ShizukuRelayPolicy.codeRefusal(code(7), setOf("priv.packages")))
    }

    @Test
    fun `everything else is refused whatever the plugin exports`() {
        val all = setOf("priv.shell", "priv.packages", "priv.settings")
        // transactRemote's raw code, system properties, user services, exit, and an id no version has.
        listOf(-1, 9, 10, 11, 12, 100, 500).forEach { assertNotNull("id $it", ShizukuRelayPolicy.codeRefusal(code(it), all)) }
    }

    @Test
    fun `a command naming droidtop's package is caught on its plain spelling`() {
        val pkg = "dev.droidtop.app"
        assertTrue(ShizukuRelayPolicy.namesPackage(listOf("pm", "clear", pkg), pkg))
        assertTrue(ShizukuRelayPolicy.namesPackage(listOf("sh", "-c", "rm -r /data/data/$pkg/files"), pkg))
        assertFalse(ShizukuRelayPolicy.namesPackage(listOf("pm", "list", "packages"), pkg))
    }

    @Test
    fun `codes are offsets from the first call`() {
        assertEquals(android.os.IBinder.FIRST_CALL_TRANSACTION + 7, code(7))
    }
}
