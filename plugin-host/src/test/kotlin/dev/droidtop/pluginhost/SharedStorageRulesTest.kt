package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An open process's file rules for shared storage (docs/plugin-api.md 5.3 and D5, Droidtop/tracker#553): the folders
 * its granted `files.shared` entries declare, and never other apps' folders under `Android`.
 */
class SharedStorageRulesTest {
    private val primary = "/storage/emulated/0"
    private val card = "/storage/1234-ABCD"

    @Test
    fun `declared folders are readable or writable on every volume`() {
        val rules = SharedFiles.brokerRules(listOf(primary, "$card/"), read = listOf("ROMs", "Saves"), write = listOf("Saves"))
        assertEquals(1, rules["$primary/ROMs"])
        assertEquals(3, rules["$primary/Saves"])
        assertEquals(3, rules["$card/Saves"])
        assertNull("a declared folder is not the whole volume", rules[primary])
        assertFalse(rules.keys.any { it.contains("ndroid") })
    }

    @Test
    fun `no grant is no rule`() {
        assertTrue(SharedFiles.brokerRules(listOf(primary), read = null, write = null).isEmpty())
    }

    @Test
    fun `a whole volume keeps Android out in every case`() {
        val rules = SharedFiles.brokerRules(listOf(primary), read = listOf("*"), write = listOf("Saves"))
        assertEquals(1, rules[primary])
        assertEquals("a folder keeps what the volume gave it", 3, rules["$primary/Saves"])
        val denied = rules.filterValues { it == 0 }.keys
        assertEquals(128, denied.size)
        assertTrue("$primary/Android" in denied)
        assertTrue("$primary/aNDROID" in denied)
    }

    @Test
    fun `paths that climb out or name other apps' folders are left out`() {
        val rules = SharedFiles.brokerRules(listOf(primary), read = listOf("../etc", "Android/data/x", "android/OBB", "Android", "Android/media/org.app"), write = null)
        assertEquals(setOf("$primary/Android/media/org.app"), rules.keys)
    }

    @Test
    fun `changing a shared-files permission is a reason to start the process again`() {
        assertEquals(setOf("files.shared.read", "files.shared.write"), SharedFiles.PERMISSIONS)
        assertEquals(8, SharedFiles.caseSpellings("abc").size)
        assertEquals(listOf("a/", "A/"), SharedFiles.caseSpellings("a/"))
    }
}
