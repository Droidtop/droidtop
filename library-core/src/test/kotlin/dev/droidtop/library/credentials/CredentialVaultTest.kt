package dev.droidtop.library.credentials

import dev.droidtop.net.SecretCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The credential vault and the move of older plain values into it (docs/SPEC.md 7h, "Credentials are stored encrypted"). */
class CredentialVaultTest {

    private class MapStore(val map: MutableMap<String, String> = mutableMapOf()) : StringStore {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** Reversible and nothing like plain text, so a test can tell sealed from stored-as-is. */
    private class FlipCipher(var broken: Boolean = false) : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray {
            if (broken) error("keystore unavailable")
            return "S".toByteArray() + plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
        override fun decrypt(sealed: ByteArray): ByteArray {
            if (broken) error("keystore unavailable")
            check(sealed.isNotEmpty() && sealed[0] == 'S'.code.toByte()) { "not sealed" }
            return sealed.drop(1).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }

    @Test
    fun `a stored value is sealed at rest and opens to the original`() {
        val store = MapStore()
        val vault = CredentialVault(store, FlipCipher())
        vault.put("k", "abc123SECRET")
        assertNotEquals("abc123SECRET", store.map.getValue("k"))
        assertFalse(store.map.getValue("k").contains("SECRET"))
        assertEquals("abc123SECRET", CredentialVault(store, FlipCipher()).get("k"))
    }

    @Test
    fun `a blank value removes the entry`() {
        val store = MapStore()
        val vault = CredentialVault(store, FlipCipher())
        vault.put("k", "value")
        vault.put("k", "  ")
        assertFalse(store.map.containsKey("k"))
        assertEquals("", vault.get("k"))
    }

    @Test
    fun `a value that cannot be opened reads as not set and is never returned as text`() {
        val store = MapStore(mutableMapOf("k" to "bm90IHNlYWxlZA=="))
        assertEquals("", CredentialVault(store, FlipCipher()).get("k"))
        val sealed = MapStore()
        CredentialVault(sealed, FlipCipher()).put("k", "value")
        assertEquals("", CredentialVault(sealed, FlipCipher(broken = true)).get("k"))
    }

    @Test
    fun `migration moves plain values into the vault and removes them from the plain store`() {
        val legacy = MapStore(mutableMapOf("a" to "one", "b" to "two", "other" to "keep"))
        val vault = CredentialVault(MapStore(), FlipCipher())
        assertEquals(2, vault.migrateFrom(legacy, listOf("a", "b", "c")))
        assertEquals("one", vault.get("a"))
        assertEquals("two", vault.get("b"))
        assertEquals(mapOf("other" to "keep"), legacy.map)
    }

    @Test
    fun `migration leaves a plain value in place when it cannot be sealed`() {
        val legacy = MapStore(mutableMapOf("a" to "one"))
        val vault = CredentialVault(MapStore(), FlipCipher(broken = true))
        assertEquals(0, vault.migrateFrom(legacy, listOf("a")))
        assertEquals("one", legacy.map["a"])
    }

    @Test
    fun `migration keeps a value already in the vault over a stale plain one and still cleans up`() {
        val legacy = MapStore(mutableMapOf("a" to "stale"))
        val vault = CredentialVault(MapStore(), FlipCipher())
        vault.put("a", "current")
        assertEquals(1, vault.migrateFrom(legacy, listOf("a")))
        assertEquals("current", vault.get("a"))
        assertTrue(legacy.map.isEmpty())
    }

    @Test
    fun `migration drops an empty plain entry without storing it`() {
        val legacy = MapStore(mutableMapOf("a" to ""))
        val vault = CredentialVault(MapStore(), FlipCipher())
        vault.migrateFrom(legacy, listOf("a"))
        assertFalse(vault.has("a"))
        assertTrue(legacy.map.isEmpty())
    }

    @Test
    fun `a second migration finds nothing to move`() {
        val legacy = MapStore(mutableMapOf("a" to "one"))
        val vault = CredentialVault(MapStore(), FlipCipher())
        vault.migrateFrom(legacy, listOf("a"))
        assertEquals(0, vault.migrateFrom(legacy, listOf("a")))
    }

    @Test
    fun `snapshot holds only the keys that are set`() {
        val vault = CredentialVault(MapStore(), FlipCipher())
        vault.put("a", "one")
        assertEquals(mapOf("a" to "one"), vault.snapshot(listOf("a", "b")))
    }

    @Test
    fun `mask shows only the last four characters of a long value`() {
        assertEquals("••••wxyz", CredentialVault.mask("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("••••", CredentialVault.mask("short"))
    }
}
