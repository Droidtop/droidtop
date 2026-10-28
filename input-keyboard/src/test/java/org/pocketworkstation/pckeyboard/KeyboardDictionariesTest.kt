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

    @Test
    fun glideKeepsSingleWordsMostFrequentFirst() {
        val lines = KeyboardDictionaries.glideLines(mapOf("the" to 222, "don't" to 120, "1,000" to 90, "ice cream" to 80, "hello" to 150, "" to 5))
        assertEquals(listOf("the\t222", "hello\t150", "don't\t120"), lines)
    }

    @Test
    fun glideKeepsAtMostItsLimit() {
        val many = (0 until KeyboardDictionaries.GLIDE_WORDS + 10).associate { "w" + it.toString(26).map { c -> 'a' + c.digitToInt(26) }.joinToString("") to it % 256 }
        assertEquals(KeyboardDictionaries.GLIDE_WORDS, KeyboardDictionaries.glideLines(many).size)
    }
}
