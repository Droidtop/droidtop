package dev.droidtop.input.chinese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pocketworkstation.pckeyboard.InputEngine

/** Droidtop/tracker#903 H8b: the engine's round trip, against a scripted librime. */
class RimeEngineTest {
    /**
     * A librime with a three-word dictionary. What is typed is [input]; picking a candidate moves its reading from
     * [input] to [picked], and the whole is committed once nothing is left to pick, as librime's selector does.
     */
    private class Scripted : RimeEngine.Runtime {
        override var state: InputEngine.State = InputEngine.State.Ready
        override var progress = -1
        var starts = 0
        var forgets = 0
        var created = 0
        val sessions = HashSet<Long>()
        val schemas = HashMap<Long, String>()
        val options = HashMap<String, Boolean>()
        val pressed = ArrayList<RimeKeys.Press>()
        private var next = 100L
        private var watcher: Runnable? = null
        private val input = StringBuilder()
        private var picked = ""
        private var highlighted = 0
        private var commit: String? = null

        /** Each word, with the reading it takes. */
        private val dictionary = mapOf(
            "nihao" to listOf("你好" to 5, "你" to 2, "尼" to 2),
            "ni" to listOf("你" to 2, "尼" to 2),
            "hao" to listOf("好" to 3, "号" to 3),
        )

        private fun words(): List<Pair<String, Int>> = dictionary[input.toString()].orEmpty()

        override fun watch(onChange: Runnable?) {
            watcher = onChange
        }

        fun becomes(next: InputEngine.State, percent: Int = -1) {
            state = next
            progress = percent
            watcher?.run()
        }

        override fun start() {
            starts++
        }

        override fun createSession(): Long {
            created++
            return (++next).also { sessions += it }
        }

        override fun hasSession(session: Long) = session in sessions

        override fun selectSchema(session: Long, schema: String): Boolean {
            schemas[session] = schema
            return schema in RimeSchemas.SCHEMAS
        }

        override fun setOption(session: Long, option: String, value: Boolean) {
            options[option] = value
        }

        private val composing get() = input.isNotEmpty() || picked.isNotEmpty()

        private fun reset() {
            input.setLength(0)
            picked = ""
            highlighted = 0
        }

        override fun processKey(session: Long, keysym: Int, mask: Int): Boolean {
            check(session in sessions)
            pressed += RimeKeys.Press(keysym, mask)
            if (options["ascii_mode"] == true) return false
            when {
                keysym in 'a'.code..'z'.code -> input.append(keysym.toChar())
                keysym == RimeKeys.BACKSPACE && composing -> if (input.isNotEmpty()) input.setLength(input.length - 1) else picked = ""
                keysym == RimeKeys.SPACE && composing -> {
                    commit = picked + (words().getOrNull(highlighted)?.first ?: input.toString())
                    reset()
                }
                keysym == RimeKeys.RETURN && composing -> {
                    commit = picked + input
                    reset()
                }
                keysym == RimeKeys.ESCAPE && composing -> reset()
                keysym == RimeKeys.DOWN && composing -> highlighted = minOf(highlighted + 1, words().size - 1)
                keysym == RimeKeys.UP && composing -> highlighted = maxOf(highlighted - 1, 0)
                // A full stop commits what is composed and is passed on, unprocessed.
                keysym == '.'.code && composing -> {
                    commit = picked + (words().firstOrNull()?.first ?: input.toString())
                    reset()
                    return false
                }
                else -> return false
            }
            return true
        }

        override fun takeCommit(session: Long): String? = commit.also { commit = null }

        override fun context(session: Long): RimeContext {
            val preedit = picked + input
            return RimeContext(preedit, preedit.length, picked.length, preedit.length,
                words().map { InputEngine.Candidate(it.first) }, if (words().isEmpty()) -1 else highlighted, 5, options["ascii_mode"] == true)
        }

        override fun selectCandidate(session: Long, index: Int): Boolean {
            val (word, reading) = words().getOrNull(index) ?: return false
            picked += word
            input.delete(0, reading)
            highlighted = 0
            if (input.isEmpty()) {
                commit = picked
                reset()
            }
            return true
        }

        override fun clear(session: Long) = reset()

        override fun forget() {
            forgets++
            sessions.clear()
        }
    }

    private val simp = RimeSchemas.forLanguage("zh_CN")!!
    private val tw = RimeSchemas.forLanguage("zh_TW")!!

    private fun type(engine: RimeEngine, text: String) = text.forEach { assertTrue(engine.onChar(it.code, null).handled) }

    @Test fun `typing pinyin composes it and offers hanzi, in one session with the language's schema and forms`() {
        val rime = Scripted()
        val engine = RimeEngine(rime, simp)
        engine.startField(suggestionsAllowed = true, password = false)
        assertEquals(1, rime.starts)
        type(engine, "nihao")
        assertEquals("nihao", engine.composing().text)
        assertEquals(5, engine.composing().caret)
        assertEquals(listOf("你好", "你", "尼"), engine.candidates().items.map { it.text.toString() })
        assertEquals(0, engine.candidates().focused)
        assertEquals(1, rime.created)
        assertEquals("luna_pinyin_simp", rime.schemas.values.single())
        assertEquals(false, rime.options["ascii_mode"])
        assertEquals(true, rime.options["zh_hans"])
        assertEquals(false, rime.options["zh_hant"])
        assertEquals(setOf(InputEngine.Capability.CONVERTS), engine.capabilities)
    }

    @Test fun `space commits the highlighted candidate, and the arrows move the highlight`() {
        val engine = RimeEngine(Scripted(), simp)
        type(engine, "nihao")
        assertTrue(engine.onKey(InputEngine.Key.DOWN).handled)
        assertEquals(1, engine.candidates().focused)
        val result = engine.onKey(InputEngine.Key.SPACE)
        assertTrue(result.handled)
        assertEquals("你", result.committed)
        assertEquals(0, result.deleteBefore)
        assertTrue(engine.composing().isEmpty)
        assertTrue(engine.candidates().isEmpty)
        // With nothing composed, space is the keyboard's.
        assertFalse(engine.onKey(InputEngine.Key.SPACE).handled)
    }

    @Test fun `a pick of part of the reading commits nothing yet and keeps composing the rest, then commits the whole`() {
        val engine = RimeEngine(Scripted(), simp)
        type(engine, "nihao")
        val first = engine.pick(1)!!
        assertEquals("", first.committed)
        assertTrue(first.composing)
        assertEquals("你hao", engine.composing().text)
        // The picked part and the rest are the composing text's two parts, the rest focused.
        assertEquals(listOf(InputEngine.Segment(0, 1), InputEngine.Segment(1, 4)), engine.composing().segments)
        assertEquals(1, engine.composing().focused)
        assertEquals(listOf("好", "号"), engine.candidates().items.map { it.text.toString() })
        val second = engine.pick(0)!!
        assertEquals("你好", second.committed)
        assertFalse(second.composing)
        assertNull(engine.pick(0))
    }

    @Test fun `commit puts in what is composed as it stands, and reset drops it`() {
        val engine = RimeEngine(Scripted(), simp)
        type(engine, "nihao")
        engine.pick(1)
        assertEquals("你hao", engine.commit().toString())
        assertTrue(engine.composing().isEmpty)
        assertEquals("", engine.commit().toString())
        type(engine, "ni")
        engine.reset()
        assertTrue(engine.composing().isEmpty)
        assertTrue(engine.candidates().isEmpty)
    }

    @Test fun `backspace takes from what is composed, and is the keyboard's when nothing is`() {
        val engine = RimeEngine(Scripted(), simp)
        assertFalse(engine.onDelete())
        type(engine, "nih")
        assertTrue(engine.onDelete())
        assertEquals("ni", engine.composing().text)
        assertTrue(engine.onDelete())
        assertTrue(engine.onDelete())
        assertTrue(engine.composing().isEmpty)
        assertFalse(engine.onDelete())
    }

    @Test fun `a character librime passes on after committing goes in with the commit`() {
        val engine = RimeEngine(Scripted(), simp)
        type(engine, "nihao")
        val result = engine.onChar('.'.code, null)
        assertTrue(result.handled)
        assertEquals("你好.", result.committed)
        // Unprocessed with nothing committed, a character is the keyboard's.
        assertFalse(engine.onChar('.'.code, null).handled)
        assertFalse(engine.onChar(0x1b, null).handled)
    }

    @Test fun `moving keys librime ignores stay the engine's while composing, so the field's caret holds still`() {
        val rime = Scripted()
        val engine = RimeEngine(rime, simp)
        type(engine, "ni")
        assertTrue(engine.onKey(InputEngine.Key.SHIFT_LEFT).handled)
        assertEquals(RimeKeys.Press(RimeKeys.LEFT, RimeKeys.SHIFT_MASK), rime.pressed.last())
        assertFalse(engine.onKey(InputEngine.Key.UNDO).handled)
        assertFalse(engine.onKey(InputEngine.Key.TOGGLE).handled)
        assertTrue(engine.onKey(InputEngine.Key.ESCAPE).handled)
        assertTrue(engine.composing().isEmpty)
    }

    @Test fun `the modes are the forms and letters, in the language's order, and set librime's options`() {
        val rime = Scripted()
        val engine = RimeEngine(rime, simp)
        assertEquals(listOf("简", "繁", "A"), engine.modes().map { it.label.toString() })
        assertEquals("simplified", engine.mode().id)
        engine.setMode(engine.modes()[1])
        assertEquals("traditional", engine.mode().id)
        assertEquals(true, rime.options["zh_hant"])
        assertEquals(false, rime.options["zh_hans"])
        type(engine, "ni")
        // Letters: what was composed is dropped, and keys are the keyboard's.
        engine.setMode(engine.modes()[2])
        assertEquals("ascii", engine.mode().id)
        assertEquals(true, rime.options["ascii_mode"])
        assertTrue(engine.composing().isEmpty)
        assertFalse(engine.onChar('a'.code, null).handled)
        // Back to Chinese, with the forms last chosen.
        engine.setMode(engine.modes()[1])
        assertEquals(false, rime.options["ascii_mode"])
        assertEquals(true, rime.options["zh_hant"])

        val traditional = RimeEngine(Scripted(), tw)
        assertEquals(listOf("繁", "简", "A"), traditional.modes().map { it.label.toString() })
        assertEquals("traditional", traditional.mode().id)
    }

    @Test fun `the mode follows librime when it switches to letters itself`() {
        val rime = Scripted()
        val engine = RimeEngine(rime, tw)
        type(engine, "ni")
        rime.options["ascii_mode"] = true
        engine.onKey(InputEngine.Key.ESCAPE)
        assertEquals("ascii", engine.mode().id)
    }

    @Test fun `while loading or unavailable no key reaches librime, and a field start retries`() {
        val rime = Scripted()
        rime.state = InputEngine.State.Loading
        rime.progress = 40
        val engine = RimeEngine(rime, simp)
        assertSame(InputEngine.State.Loading, engine.state)
        assertEquals(40, engine.progress)
        assertFalse(engine.onChar('n'.code, null).handled)
        assertFalse(engine.onKey(InputEngine.Key.SPACE).handled)
        assertNull(engine.pick(0))
        assertEquals(0, rime.created)
        var told = 0
        engine.watchState { told++ }
        rime.becomes(InputEngine.State.Ready)
        assertEquals(1, told)
        rime.becomes(InputEngine.State.Unavailable("no connection"))
        engine.startField(suggestionsAllowed = true, password = false)
        assertEquals(1, rime.starts)
        assertFalse(engine.onChar('n'.code, null).handled)
    }

    @Test fun `a session librime lost is made again with the schema and mode`() {
        val rime = Scripted()
        val engine = RimeEngine(rime, tw)
        type(engine, "ni")
        engine.reset()
        rime.sessions.clear()
        rime.options.clear()
        type(engine, "ni")
        assertEquals(2, rime.created)
        assertEquals("luna_pinyin_tw", rime.schemas.values.last())
        assertEquals(true, rime.options["zh_hant_tw"])
        assertEquals(false, rime.options["ascii_mode"])
    }

    @Test fun `password fields are not composed, and forgetting is the runtime's`() {
        val rime = Scripted()
        val engine = RimeEngine(rime, simp)
        engine.startField(suggestionsAllowed = true, password = true)
        assertFalse(engine.composes())
        engine.startField(suggestionsAllowed = false, password = false)
        assertTrue(engine.composes())
        type(engine, "ni")
        engine.forgetLearned()
        assertEquals(1, rime.forgets)
        assertTrue(engine.composing().isEmpty)
        assertNotNull(engine.onChar('n'.code, null))
    }
}
