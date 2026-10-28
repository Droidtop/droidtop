package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's dictionary format (Droidtop/tracker#339). [GOLDEN] is the fixture's dictionary as the native reader
 * (src/main/cpp/dictionary.cpp, built on a host) read it back: every word valid, completions ranked by frequency,
 * "Robin" and "robin" apart, and the bigrams after "the", "hello" and "it" found. The writer must produce exactly it.
 */
class DictionaryWriterTest {
    private val words = linkedMapOf(
        "the" to 222, "they" to 180, "then" to 170, "there" to 175, "this" to 200, "is" to 210, "it" to 205, "hello" to 150,
        "help" to 160, "world" to 140, "word" to 130, "work" to 145, "Robin" to 90, "robin" to 60, "don't" to 120, "a" to 220,
    )
    private val bigrams = mapOf(
        "the" to listOf("world" to 100, "work" to 90, "word" to 40),
        "hello" to listOf("world" to 120),
        "it" to listOf("is" to 110),
    )

    @Test
    fun writesWhatTheNativeReaderRead() {
        assertArrayEquals(hex(GOLDEN), DictionaryWriter.write(words, bigrams))
    }

    @Test
    fun theHeaderNamesVersion200AndBigrams() {
        val bytes = DictionaryWriter.write(words, bigrams)
        assertEquals(200, bytes[0].toInt() and 0xFF)
        assertEquals(1, bytes[1].toInt())
    }

    @Test
    fun aWordPastU00FFMeansNoBigrams() {
        val bytes = DictionaryWriter.write(mapOf("zażółć" to 100, "gęślą" to 90), mapOf("zażółć" to listOf("gęślą" to 50)))
        assertEquals(0, bytes[1].toInt())
    }

    @Test
    fun tooManyWordsAreThinnedByFrequencyUntilTheyFit() {
        val many = (0 until 400_000).associate { i -> "w" + i.toString(36) + "x".repeat(i % 7) to (i % 255) }
        val bytes = DictionaryWriter.writeFitting(many, emptyMap())
        assertTrue(bytes.size <= DictionaryWriter.ADDRESS_LIMIT)
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        const val GOLDEN =
            "c80108524000236180dc00644000376840004b694000667240007274400086774000b8016f400028016240002d0169400032016e805a00016f40003c" +
                "016e400041012740004601748078000165400050016c400055026c40005e7080a000016f80968000d078027380d2007480cd8000676e016f40007701" +
                "6240007c0169400081016e803c00016840008b0265c000a1de8000d0e48000c7da8000c328694000b3036e80aa00724000ae7980b400016580af0001" +
                "7380c800016f4000bd01724000c203648082006b8091006c4000cf0164808c00"
    }
}
