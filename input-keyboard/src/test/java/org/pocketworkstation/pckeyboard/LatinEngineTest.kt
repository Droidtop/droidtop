package org.pocketworkstation.pckeyboard

import android.view.View
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hacker's Latin path as the first engine (Droidtop/tracker#903 H6): the old code ([LatinOldPath], LatinIME's own
 * methods copied word for word) and [LatinEngine] run side by side on the same inputs and must agree on everything:
 * where Hacker's composes, the strip's suggestions with how it draws them and the word a separator types, and the
 * composing text through typing, deleting, committing and resetting.
 */
class LatinEngineTest {
    /** A dictionary as Suggest answers: the typed word first, then words it starts or is one letter from, in the typed case. */
    private class FakeLexicon(private val words: List<String>, private val correcting: Boolean) : LatinEngine.Lexicon {
        private val shown = ArrayList<CharSequence>()
        private var haveCorrection = false
        private val next = IntArray(1280)

        override fun getSuggestions(view: View?, wordComposer: WordComposer, includeTypedWordIfValid: Boolean, prevWordForBigram: CharSequence?): List<CharSequence> {
            shown.clear()
            val typed = wordComposer.typedWord?.toString() ?: return shown.also { haveCorrection = false }
            shown += typed
            val lower = typed.lowercase()
            for (w in words) {
                if (w != lower && (w.startsWith(lower) || oneApart(w, lower))) {
                    shown += if (wordComposer.isAllUpperCase) w.uppercase() else if (wordComposer.isFirstCharCapitalized) w.replaceFirstChar { it.uppercaseChar() } else w
                }
            }
            if (prevWordForBigram == "the") shown.add(minOf(1, shown.size), "end")
            haveCorrection = correcting && shown.size > 1
            next.fill(0)
            for (w in words) if (w.startsWith(lower) && w.length > lower.length) next[w[lower.length].code] += 1
            return shown
        }

        override fun getNextLettersFrequencies(): IntArray = next
        override fun hasMinimalCorrection(): Boolean = haveCorrection
        override fun isValidWord(word: CharSequence?): Boolean = word != null && word.toString() in words

        private fun oneApart(a: String, b: String) = a.length == b.length && a.indices.count { a[it] != b[it] } == 1
    }

    private val dictionary = listOf("the", "they", "then", "hello", "help", "word", "world", "work", "i", "it")

    private fun composer(typed: String, firstCapital: Boolean = typed.firstOrNull()?.isUpperCase() == true) = WordComposer().apply {
        for (c in typed) add(c.code, intArrayOf(c.code))
        setFirstCharCapitalized(firstCapital)
    }

    private class Flags(var forMode: Boolean = true, var show: Boolean = true, var forceOn: Boolean = false, var forceOff: Boolean = false,
        var landscape: Boolean = true, var portrait: Boolean = true, var correction: Int = Suggest.CORRECTION_FULL) : LatinEngine.Host {
        override fun predictionOnForMode() = forMode
        override fun showSuggestions() = show
        override fun suggestionForceOn() = forceOn
        override fun suggestionForceOff() = forceOff
        override fun suggestionsInLandscape() = landscape
        override fun portrait() = portrait
        override fun correctionMode() = correction
    }

    @Test fun `it composes exactly where Hacker's did, for every combination of field, settings and orientation`() {
        for (bits in 0 until 64) {
            val f = Flags(forMode = bits and 1 != 0, show = bits and 2 != 0, forceOn = bits and 4 != 0, forceOff = bits and 8 != 0,
                landscape = bits and 16 != 0, portrait = bits and 32 != 0)
            val disabled = LatinOldPath.suggestionsDisabled(f.forceOff, f.forceOn, f.landscape, f.portrait)
            val wanted = LatinOldPath.isPredictionWanted(f.show, f.forceOn, disabled)
            val on = LatinOldPath.isPredictionOn(f.forMode, wanted)
            val engine = LatinEngine(f)
            assertEquals("disabled $bits", disabled, engine.suggestionsDisabled())
            assertEquals("wanted $bits", wanted, engine.predictionWanted())
            assertEquals("on $bits", on, engine.composes())
            assertEquals("bar $bits", on, engine.wantsCandidateBar())
            assertEquals("predicts $bits", on, InputEngine.Capability.PREDICTS in engine.capabilities)
            assertFalse(InputEngine.Capability.CONVERTS in engine.capabilities)
        }
    }

    @Test fun `suggestions off and the terminal preset compose nothing, as before`() {
        // The Terminal preset writes "Show suggestions" off (KeyboardProMode); a password field clears forMode.
        val off = LatinEngine(Flags(show = false))
        assertFalse(off.composes())
        assertTrue(off.capabilities.isEmpty())
        assertFalse(LatinEngine(Flags(forMode = false)).composes())
        assertFalse(LatinEngine(Flags(landscape = false, portrait = false)).composes())
        assertTrue(LatinEngine(Flags(correction = Suggest.CORRECTION_BASIC)).capabilities.contains(InputEngine.Capability.CORRECTS))
        assertFalse(LatinEngine(Flags(correction = Suggest.CORRECTION_NONE)).capabilities.contains(InputEngine.Capability.CORRECTS))
    }

    @Test fun `the strip offers the same suggestions, drawn the same, with the same word for a separator`() {
        val inputs = listOf("th", "the", "The", "THE", "Th", "helo", "Helo", "wrld", "wor", "xyz", "i", "I", "It", "worl", "HElp")
        var cases = 0
        for (typed in inputs) for (firstCapital in listOf(false, true)) for (mode in 0..3) for (noAuto in listOf(false, true))
            for (correcting in listOf(false, true)) for (prev in listOf<CharSequence?>(null, "the")) for (lexCorrects in listOf(false, true)) {
                val label = "$typed caps=$firstCapital mode=$mode noAuto=$noAuto correcting=$correcting prev=$prev corrects=$lexCorrects"
                val oldWord = composer(typed, firstCapital)
                val old = LatinOldPath.showSuggestions(FakeLexicon(dictionary, lexCorrects), oldWord, prev, noAuto, mode, correcting, oldWord)
                val oldList = old.stringList.map { it.toString() }

                val engine = LatinEngine(Flags(correction = mode))
                engine.setLexicon(FakeLexicon(dictionary, lexCorrects))
                engine.word = composer(typed, firstCapital)
                val now = engine.suggest(null, prev, noAuto, correcting)
                assertEquals(label, oldList, now.words.map { it.toString() })
                assertEquals(label, old.typedWord?.toString(), now.typedWord?.toString())
                assertEquals(label, old.typedWordValid, now.typedWordValid)
                assertEquals(label, old.correctionAvailable, now.correctionAvailable)
                assertArrayEquals(label, old.nextLettersFrequencies, now.nextLetters)
                val best = LatinEngine.bestWord(now.words, now.typedWord, now.typedWordValid, now.correctionAvailable)
                assertEquals(label, old.mBestWord?.toString(), best?.toString())
                // The engine's candidates are the strip's words, in order.
                assertEquals(label, oldList, engine.candidates().items.map { it.text.toString() })
                cases++
            }
        assertEquals(inputs.size * 2 * 4 * 2 * 2 * 2 * 2, cases)
    }

    @Test fun `an empty list types no word at a separator`() {
        assertNull(LatinEngine.bestWord(emptyList(), "x", false, true))
        assertNull(LatinOldPath.showSuggestions(object : LatinEngine.Lexicon {
            override fun getSuggestions(view: View?, wordComposer: WordComposer, includeTypedWordIfValid: Boolean, prevWordForBigram: CharSequence?) = emptyList<CharSequence>()
            override fun getNextLettersFrequencies() = IntArray(0)
            override fun hasMinimalCorrection() = false
            override fun isValidWord(word: CharSequence?) = false
        }, composer("ab"), null, false, 2, false, composer("ab")).mBestWord)
    }

    @Test fun `typing, deleting, committing and resetting leave the same composing text as before`() {
        val script = listOf("start", "h", "e", "l", "del", "l", "o", "commit", "start", "W", "o", "del", "del", "del", "x",
            "start", "a", "reset", "start", "q", "u", "commit", "commit", "del")
        val old = LatinOldPath.Composing()
        val engine = LatinEngine(Flags())
        for ((step, op) in script.withIndex()) {
            var oldCommitted: CharSequence? = null
            var newCommitted: CharSequence? = null
            when (op) {
                "start" -> {
                    old.start()
                    engine.predicting = true
                    engine.composing.setLength(0)
                    engine.word.reset()
                }
                "del" -> {
                    old.backspace()
                    // LatinIME's handleBackspace, with the engine.
                    if (engine.predicting && engine.composing.isNotEmpty()) {
                        assertTrue(engine.onDelete())
                        if (engine.composing.isEmpty()) engine.predicting = false
                    } else {
                        assertFalse(engine.onDelete())
                    }
                }
                "commit" -> {
                    oldCommitted = old.commitTyped()
                    if (engine.predicting) newCommitted = engine.commit().takeIf { it.isNotEmpty() } else assertEquals("", engine.commit().toString())
                }
                "reset" -> {
                    old.reset()
                    engine.reset()
                }
                else -> {
                    val c = op[0].code
                    old.type(c, intArrayOf(c, 'z'.code))
                    assertEquals(old.mPredicting, engine.onChar(c, intArrayOf(c, 'z'.code)))
                }
            }
            val label = "step $step ($op)"
            assertEquals(label, old.mComposing.toString(), engine.composing.toString())
            assertEquals(label, old.mPredicting, engine.predicting)
            assertEquals(label, old.mWord.typedWord?.toString(), engine.word.typedWord?.toString())
            assertEquals(label, old.mWord.size(), engine.word.size())
            assertEquals(label, oldCommitted?.toString(), newCommitted?.toString())
            assertEquals(label, if (old.mPredicting) old.mComposing.toString() else "", engine.composing().text.toString())
        }
    }

    @Test fun `a commit gives Hacker's own buffer, kept for a revert, and the interface sees nothing composed after`() {
        val engine = LatinEngine(Flags())
        engine.predicting = true
        engine.onChar('h'.code, intArrayOf('h'.code))
        engine.onChar('i'.code, intArrayOf('i'.code))
        val committed = engine.commit()
        assertSame(engine.composing, committed)
        assertEquals("hi", engine.composing.toString())
        assertTrue(engine.composing().isEmpty)
        assertEquals(InputEngine.KeyResult.NOT_HANDLED, engine.onKey(InputEngine.Key.SPACE))
    }

    @Test fun `a picked candidate ends composing`() {
        val engine = LatinEngine(Flags())
        engine.setLexicon(FakeLexicon(dictionary, true))
        engine.predicting = true
        "wor".forEach { engine.onChar(it.code, intArrayOf(it.code)) }
        engine.suggest(null, null, false, false)
        assertEquals("word", engine.pick(1).toString())
        assertFalse(engine.predicting)
        assertNull(engine.pick(99))
    }
}
