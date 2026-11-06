package org.pocketworkstation.pckeyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * The gesture seam (Droidtop/tracker#903 H6): droidtop's classifier behind [GestureEngine] ranks every stroke exactly
 * as glide typing ranked it before (the classifier built with GlideTyping's old parameters and fed point by point),
 * and the library slot falls back to it while no library is set or a library declines.
 */
class GestureEngineTest {
    @After fun noLibrary() {
        GestureEngines.library = GestureLibrary.NONE
    }

    /** QWERTY keys [size] pixels wide, as GlideTyping hands them over. */
    private fun keys(size: Int) = listOf(Triple("qwertyuiop", 0, 0), Triple("asdfghjkl", size / 2, size), Triple("zxcvbnm", size * 3 / 2, size * 2))
        .flatMap { (letters, offset, top) -> letters.mapIndexed { i, c -> GestureKey(c.toString(), offset + i * size, top, size, size) } }

    private val words = listOf("hello", "help", "world", "word", "the", "they", "then", "test", "tea", "quiet", "quite", "zebra", "as",
        "work", "wore", "were", "where", "there", "three", "tree", "great", "greet", "hold", "gold", "jolly")
    private val frequencies = IntArray(words.size) { 250 - it * 7 }

    /** A stroke through [word]'s keys, [steps] points a key, pushed off the line by up to [wobble] pixels. */
    private fun stroke(layout: GestureLayout, word: String, steps: Int, wobble: Int): List<Pair<Int, Int>> {
        val byLetter = layout.keys.associateBy { it.letters[0] }
        val centres = word.toList().fold(mutableListOf<Char>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }
            .map { byLetter.getValue(it).let { k -> (k.left + k.width / 2) to (k.top + k.height / 2) } }
        val points = ArrayList<Pair<Int, Int>>()
        for ((a, b) in centres.zipWithNext()) {
            for (s in 0 until steps) {
                val t = s.toDouble() / steps
                val j = if (wobble == 0) 0 else (s * 37) % (2 * wobble + 1) - wobble
                points += (a.first + ((b.first - a.first) * t).toInt() + j) to (a.second + ((b.second - a.second) * t).toInt() - j)
            }
        }
        points += centres.last()
        return points
    }

    /** Glide typing before the seam: GlideTyping's classifier, fed every point of the touch, scored at release. */
    private fun oldWords(layout: GestureLayout, points: List<Pair<Int, Int>>): List<String> {
        val keyWidth = max(1, layout.keys.map { it.width }.sorted()[layout.keys.size / 2])
        val c = GlideClassifier(layout.keys.map { GlideClassifier.Key(it.letters, it.left, it.top, it.width, it.height) },
            minPointDistance = max(4, keyWidth / 6), startKeyProximity = keyWidth * 3 / 2)
        c.load(words, frequencies)
        c.clear()
        for ((x, y) in points) c.addPoint(x, y)
        val path = c.path() ?: return emptyList()
        return c.candidates(path)
    }

    private fun newWords(engine: GestureEngine, points: List<Pair<Int, Int>>): List<String> {
        val s = GestureStroke()
        for ((i, p) in points.withIndex()) s.add(p.first, p.second, 1000L + i * 8)
        return engine.recognize(s.copy())
    }

    @Test fun `the classifier behind the seam ranks every stroke as before`() {
        var compared = 0
        for (size in listOf(60, 100, 137)) {
            val layout = GestureLayout(keys(size))
            val engine = GestureEngines.create(layout, words, frequencies)
            assertTrue(engine is ClassifierGestureEngine)
            for (word in words) for (steps in listOf(2, 8, 30)) for (wobble in listOf(0, 7, 20)) {
                val points = stroke(layout, word, steps, wobble)
                val before = oldWords(layout, points)
                assertEquals("$word size=$size steps=$steps wobble=$wobble", before, newWords(engine, points))
                if (before.isNotEmpty()) compared++
            }
        }
        assertTrue("most strokes guess something ($compared)", compared > words.size * 3 * 9 / 2)
    }

    @Test fun `a stroke longer than the classifier keeps is cut where it was cut`() {
        val layout = GestureLayout(keys(100))
        val engine = GestureEngines.create(layout, words, frequencies)
        // Back and forth along the top row far past the classifier's 1024 kept points.
        val points = (0 until 6000).map { i -> (50 + (i * 23) % 900) to (50 + (i % 3)) }
        assertEquals(oldWords(layout, points), newWords(engine, points))
        assertEquals(emptyList<String>(), newWords(engine, emptyList()))
    }

    @Test fun `a stroke carries its timing and copies are fixed`() {
        val s = GestureStroke(2)
        for (i in 0 until 5) s.add(i, i * 2, 100L + i)
        val copy = s.copy()
        s.clear()
        s.add(9, 9, 9)
        assertEquals(5, copy.size)
        assertEquals(104L, copy.time(4))
        assertEquals(8, copy.y(4))
        assertEquals(1, s.size)
    }

    @Test fun `with no library the classifier is used, and a library is used only when it serves the keyboard`() {
        val layout = GestureLayout(keys(100))
        assertSame(GestureLibrary.NONE, GestureEngines.library)
        assertTrue(GestureLibrary.NONE.create(layout, words, frequencies) == null)

        val fixed = object : GestureEngine {
            override fun recognize(stroke: GestureStroke) = listOf("library")
        }
        GestureEngines.library = object : GestureLibrary {
            override fun create(layout: GestureLayout, words: List<String>, frequencies: IntArray) = fixed
        }
        assertSame(fixed, GestureEngines.create(layout, words, frequencies))

        GestureEngines.library = object : GestureLibrary {
            override fun create(layout: GestureLayout, words: List<String>, frequencies: IntArray): GestureEngine? = null
        }
        assertTrue(GestureEngines.create(layout, words, frequencies) is ClassifierGestureEngine)
    }

    @Test fun `the layout's key width is the median letter key's, as glide typing took it`() {
        val keys = keys(100).toMutableList()
        keys[0] = GestureKey("q", 0, 0, 400, 100)
        assertEquals(100, GestureLayout(keys).keyWidth)
        assertEquals(1, GestureLayout(emptyList()).keyWidth)
    }
}
