package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which language a wordlist is kept for (Droidtop/tracker#339). */
class KeyboardDictionariesTest {
    @Test
    fun theLanguageComesFromTheHeader() {
        assertEquals("en", KeyboardDictionaries.languageOf(mapOf("dictionary" to "main:en_us", "locale" to "en_US")))
        assertEquals("de", KeyboardDictionaries.languageOf(mapOf("dictionary" to "main:de")))
        assertEquals("pt", KeyboardDictionaries.languageOf(mapOf("locale" to "pt-BR")))
        assertNull(KeyboardDictionaries.languageOf(emptyMap()))
    }

    @Test
    fun aLocaleIsKeptByItsLanguageCode() {
        assertEquals("en", KeyboardDictionaries.code("en_US"))
        assertEquals("en", KeyboardDictionaries.code("EN"))
        assertEquals("fr", KeyboardDictionaries.code("fr-CA"))
    }
}
