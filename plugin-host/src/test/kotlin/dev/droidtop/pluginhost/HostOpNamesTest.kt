package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host op names (docs/plugin-api.md 3 "Naming", Droidtop/tracker#459): every op follows the rule, and every old name still answers. */
class HostOpNamesTest {
    @Test
    fun `every host op droidtop serves follows the naming rule`() {
        val broken = HostApis.all().mapNotNull { op -> HostOpNames.check(op.op)?.let { "${op.api}.${op.op}: $it" } }
        assertTrue(broken.joinToString("\n"), broken.isEmpty())
    }

    @Test
    fun `every deprecated name finds the op it was renamed to`() {
        for ((old, new) in HostOpNames.DEPRECATED) {
            val found = HostApis.find(old.substringBeforeLast('.'), old.substringAfterLast('.'))
            assertNotNull(old, found)
            assertEquals(old, new, "${found!!.api}.${found.op}")
            assertTrue(old, HostApis.isHostApi(old.substringBeforeLast('.')))
        }
    }

    @Test
    fun `the rule names what breaks it`() {
        assertNull(HostOpNames.check("get_status", readOnly = true))
        assertNull(HostOpNames.check("open_in_session"))
        assertTrue(HostOpNames.check("status")!!.contains("noun"))
        assertTrue(HostOpNames.check("changed")!!.contains("event"))
        assertTrue(HostOpNames.check("Refresh")!!.contains("lower-case"))
        assertTrue(HostOpNames.check("sources", readOnly = true)!!.contains("only reads"))
    }
}
