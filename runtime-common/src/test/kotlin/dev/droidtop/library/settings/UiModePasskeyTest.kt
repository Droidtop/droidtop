package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Leaving Kid and Kiosk with the optional passkey (UiModePasskey in UiMode.kt). */
class UiModePasskeyTest {

    @Test
    fun `no passkey leaves at once`() {
        assertTrue(UiModePasskey.matches(null, ""))
        assertTrue(UiModePasskey.matches(null, "1234"))
    }

    @Test
    fun `a wrong passkey stays and the right one leaves`() {
        val stored = UiModePasskey.make("2468", salt = "abc")
        assertFalse(UiModePasskey.matches(stored, "1357"))
        assertFalse(UiModePasskey.matches(stored, ""))
        assertFalse(UiModePasskey.matches(stored, "24680"))
        assertTrue(UiModePasskey.matches(stored, "2468"))
    }

    @Test
    fun `the passkey is stored hashed and salted, never as its digits`() {
        val stored = UiModePasskey.make("2468", salt = "abc")
        assertFalse(stored.hash.contains("2468"))
        assertEquals(64, stored.hash.length)
        assertNotEquals(stored.hash, UiModePasskey.make("2468", salt = "abd").hash)
        // Two passkeys made with fresh salts differ even for the same digits.
        assertNotEquals(UiModePasskey.make("2468").salt, UiModePasskey.make("2468").salt)
    }

    @Test
    fun `a passkey is four to eight digits`() {
        assertTrue(UiModePasskey.valid("0000"))
        assertTrue(UiModePasskey.valid("12345678"))
        assertFalse(UiModePasskey.valid("123"))
        assertFalse(UiModePasskey.valid("123456789"))
        assertFalse(UiModePasskey.valid("12a4"))
    }
}
