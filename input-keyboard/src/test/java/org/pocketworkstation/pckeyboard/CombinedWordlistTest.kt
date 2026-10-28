package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The `.combined` wordlist reader (Droidtop/tracker#339). */
class CombinedWordlistTest {
    private val text = """
        dictionary=main:en_us,locale=en_US,description=English (US),date=1414726273,version=54
         word=the,f=222,flags=,originalFreq=222
          bigram=world,f=200
          bigram=work,f=3
         word=hello,f=150,flags=,originalFreq=150
          shortcut=hi,f=whitelist
         word=world,f=140
         word=work,f=145
         word=asdf,f=10,not_a_word=true
          bigram=the,f=100
         word=1,000,f=90
    """.trimIndent()

    private val parsed = CombinedWordlist.parse(text.lineSequence())

    @Test
    fun readsTheHeaderWordsAndFrequencies() {
        assertEquals("en_US", parsed.header["locale"])
        assertEquals(mapOf("the" to 222, "hello" to 150, "world" to 140, "work" to 145, "1,000" to 90), parsed.words)
    }

    @Test
    fun bigramsBelongToTheWordAboveThemAndFitSevenBits() {
        assertEquals(listOf("world" to 100, "work" to 1), parsed.bigrams["the"])
    }

    @Test
    fun aNotAWordEntryAndItsBigramsAreSkipped() {
        assertFalse("asdf" in parsed.words)
        assertFalse("asdf" in parsed.bigrams)
    }

    @Test
    fun aParsedListWritesADictionary() {
        val bytes = DictionaryWriter.write(parsed.words, parsed.bigrams)
        assertEquals(1, bytes[1].toInt())
    }
}
