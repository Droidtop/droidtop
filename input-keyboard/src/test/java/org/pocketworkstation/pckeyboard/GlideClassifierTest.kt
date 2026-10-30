package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Glide typing's word guesser (Droidtop/tracker#341) on a QWERTY grid of 100-pixel keys, with strokes drawn through
 * each word's keys and wobbled, as a finger draws them.
 */
class GlideClassifierTest {
    private val keys = listOf(Triple("qwertyuiop", 0, 0), Triple("asdfghjkl", 50, 100), Triple("zxcvbnm", 150, 200))
        .flatMap { (letters, offset, top) -> letters.mapIndexed { i, c -> GlideClassifier.Key(c.toString(), offset + i * 100, top, 100, 100) } }
    private val byLetter = keys.associateBy { it.letters[0] }

    private val words = listOf("hello", "help", "world", "word", "the", "they", "then", "test", "tea", "quiet", "quite", "zebra", "as")
    private val frequencies = intArrayOf(180, 170, 160, 150, 220, 190, 185, 140, 120, 100, 110, 60, 200)

    private fun classifier() = GlideClassifier(keys).apply { load(words, frequencies) }

    /** A stroke through [word]'s keys, eight points a step, each pushed off the straight line by up to 7 pixels. */
    private fun stroke(word: String): List<Pair<Int, Int>> {
        val centres = word.toList().fold(mutableListOf<Char>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }
            .map { byLetter.getValue(it).let { k -> k.centerX to k.centerY } }
        val points = ArrayList<Pair<Int, Int>>()
        for ((a, b) in centres.zipWithNext()) {
            for (s in 0 until 8) {
                val t = s / 8.0
                val j = (s * 37) % 15 - 7
                points += (a.first + ((b.first - a.first) * t).toInt() + j) to (a.second + ((b.second - a.second) * t).toInt() - j)
            }
        }
        points += centres.last()
        return points
    }

    private fun glide(c: GlideClassifier, word: String): List<String> {
        c.clear()
        for ((x, y) in stroke(word)) c.addPoint(x, y)
        return c.candidates()
    }

    @Test
    fun `a stroke through a word's keys guesses that word first`() {
        val c = classifier()
        for (word in listOf("world", "they", "quiet", "zebra", "word")) {
            assertEquals(word, word, glide(c, word).first())
        }
    }

    @Test
    fun `words sharing a shape are offered too`() {
        val c = classifier()
        val they = glide(c, "they")
        assertTrue(they.toString(), "then" in they && "the" in they)
    }

    @Test
    fun `nothing is guessed before the words are loaded or with no stroke`() {
        val c = GlideClassifier(keys)
        c.addPoint(10, 10)
        assertEquals(emptyList<String>(), c.candidates())
        val loaded = classifier()
        assertEquals(emptyList<String>(), loaded.candidates())
    }

    @Test
    fun `a path handed off is scored as the stroke it was, whatever the next touch does`() {
        val c = classifier()
        val live = glide(c, "world")
        val path = c.path()!!
        c.clear()
        c.addPoint(10, 10)
        assertEquals(live, c.candidates(path))
        c.clear()
        assertEquals(null, c.path())
    }

    @Test
    fun `a gesture's corners are its ends and its sharp turns`() {
        val straight = GlideClassifier.corners(intArrayOf(0, 50, 100), intArrayOf(0, 0, 0), 3)
        assertEquals(listOf<Short>(0, 0, 100, 0), straight.toList())
        val turn = GlideClassifier.corners(intArrayOf(0, 100, 100), intArrayOf(0, 0, 100), 3)
        assertEquals(listOf<Short>(0, 0, 100, 0, 100, 100), turn.toList())
    }

    @Test
    fun `direction agreement scales the distance`() {
        assertEquals(1.0, GlideClassifier.cosine(1.0, 0.0, 2.0, 0.0), 1e-9)
        assertEquals(-1.0, GlideClassifier.cosine(1.0, 0.0, -3.0, 0.0), 1e-9)
        assertEquals(1.0, GlideClassifier.cosine(0.0, 0.0, 1.0, 0.0), 1e-9)
    }
}
