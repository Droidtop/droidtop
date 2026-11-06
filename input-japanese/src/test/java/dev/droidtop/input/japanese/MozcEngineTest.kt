package dev.droidtop.input.japanese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateList
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateWord
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.CompositionMode
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.DeletionRange
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Preedit
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Result
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import org.pocketworkstation.pckeyboard.InputEngine

/** Droidtop/tracker#903 H7c: the engine's round trip, against a scripted Mozc. */
class MozcEngineTest {
    /** A Mozc that answers each command from [reply], and records what it was sent. */
    private class Scripted(var reply: (Input) -> Output) : MozcEngine.Runtime {
        override var state: InputEngine.State = InputEngine.State.Ready
        override var progress = -1
        val sent = ArrayList<Input>()
        var starts = 0
        var syncs = 0
        var forgets = 0
        var nextSession = 41L
        private var watcher: Runnable? = null

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

        override fun eval(input: Input): Output {
            check(state == InputEngine.State.Ready)
            sent += input
            if (input.type == Input.CommandType.CREATE_SESSION) return Output.newBuilder().setId(++nextSession).build()
            if (input.type == Input.CommandType.SET_REQUEST) return Output.getDefaultInstance()
            return reply(input)
        }

        override fun sync() {
            syncs++
        }

        override fun forget() {
            forgets++
        }
    }

    private fun reading(text: String) = Output.newBuilder().setConsumed(true).setPreedit(
        Preedit.newBuilder().setCursor(text.codePointCount(0, text.length)).addSegment(
            Preedit.Segment.newBuilder().setValue(text).setValueLength(text.codePointCount(0, text.length)).setAnnotation(Preedit.Segment.Annotation.UNDERLINE),
        ),
    ).setAllCandidateWords(
        CandidateList.newBuilder()
            .addCandidates(CandidateWord.newBuilder().setId(0).setValue("日本語"))
            .addCandidates(CandidateWord.newBuilder().setId(5).setValue("日本")),
    ).build()

    @Test fun `typing romaji composes kana and offers candidates, in one session with the field's context`() {
        val mozc = Scripted { if (it.key.keyCode == 'o'.code) reading("にほんご") else reading("にほんg") }
        val engine = MozcEngine(mozc)
        engine.startField(suggestionsAllowed = true, password = false)
        engine.textBeforeCaret("今日は")
        assertTrue(engine.onChar('g'.code, null).handled)
        val r = engine.onChar('o'.code, null)
        assertTrue(r.handled)
        assertNull(r.committed)
        assertEquals("にほんご", engine.composing().text.toString())
        assertEquals(listOf("日本語", "日本"), engine.candidates().items.map { it.text.toString() })

        val types = mozc.sent.map { it.type }
        assertEquals(listOf(Input.CommandType.CREATE_SESSION, Input.CommandType.SET_REQUEST, Input.CommandType.SEND_KEY, Input.CommandType.SEND_KEY), types)
        val key = mozc.sent.last()
        assertEquals("every key is in the session Mozc made", 42L, key.id)
        assertEquals("今日は", key.context.precedingText)
        assertTrue(mozc.sent[1].request.mixedConversion)
        assertTrue(mozc.sent[1].request.zeroQuerySuggestion)
    }

    @Test fun `a key Mozc does not consume is left to the keyboard`() {
        val mozc = Scripted { Output.newBuilder().setConsumed(false).build() }
        val engine = MozcEngine(mozc)
        assertFalse(engine.onKey(InputEngine.Key.ENTER).handled)
        assertFalse("Backspace with nothing composed is the field's", engine.onDelete())
    }

    @Test fun `a pick sends the candidate's id and a partial commit keeps composing`() {
        val mozc = Scripted { input ->
            when {
                input.type == Input.CommandType.SEND_COMMAND && input.command.type == SessionCommand.CommandType.SUBMIT_CANDIDATE ->
                    Output.newBuilder().setConsumed(true)
                        .setResult(Result.newBuilder().setType(Result.ResultType.STRING).setValue("日本"))
                        .setPreedit(Preedit.newBuilder().setCursor(1).addSegment(Preedit.Segment.newBuilder().setValue("ご").setValueLength(1).setAnnotation(Preedit.Segment.Annotation.UNDERLINE)))
                        .build()
                else -> reading("にほんご")
            }
        }
        val engine = MozcEngine(mozc)
        engine.onChar('o'.code, null)
        val picked = engine.pick(1)
        assertNotNull(picked)
        assertEquals("日本", picked!!.committed.toString())
        assertTrue("the rest stays composing", picked.composing)
        assertEquals(5, mozc.sent.last().command.id)
        assertEquals("ご", engine.composing().text.toString())
        assertNull("no such candidate", engine.pick(9))
    }

    @Test fun `commit submits, reset resets the context, and undo deletes the commit before the caret`() {
        val mozc = Scripted { input ->
            when (input.command.type) {
                SessionCommand.CommandType.SUBMIT -> Output.newBuilder().setConsumed(true)
                    .setResult(Result.newBuilder().setType(Result.ResultType.STRING).setValue("日本語")).build()
                SessionCommand.CommandType.UNDO_OR_REWIND -> Output.newBuilder().setConsumed(true)
                    .setDeletionRange(DeletionRange.newBuilder().setOffset(-2).setLength(2))
                    .setPreedit(Preedit.newBuilder().setCursor(2).addSegment(Preedit.Segment.newBuilder().setValue("𠮷野").setValueLength(2).setAnnotation(Preedit.Segment.Annotation.UNDERLINE)))
                    .build()
                SessionCommand.CommandType.RESET_CONTEXT -> Output.newBuilder().setConsumed(true).build()
                else -> reading("にほんご")
            }
        }
        val engine = MozcEngine(mozc)
        engine.onChar('o'.code, null)
        assertEquals("日本語", engine.commit().toString())
        engine.textBeforeCaret("は𠮷野")
        val undo = engine.onKey(InputEngine.Key.UNDO)
        assertTrue(undo.handled)
        assertEquals("two code points before the caret are three chars", 3, undo.deleteBefore)
        assertEquals("𠮷野", engine.composing().text.toString())
        engine.reset()
        assertEquals(SessionCommand.CommandType.RESET_CONTEXT, mozc.sent.last().command.type)
        assertTrue(engine.composing().isEmpty)
        assertTrue(engine.candidates().isEmpty)
    }

    @Test fun `modes switch the request and Mozc's composition mode`() {
        val mozc = Scripted { Output.newBuilder().setConsumed(true).setMode(it.command.compositionMode).build() }
        val engine = MozcEngine(mozc)
        assertEquals(listOf("あ", "ア", "A"), engine.modes().map { it.label.toString() })
        assertEquals("あ", engine.mode().label.toString())
        engine.setMode(engine.modes()[2])
        val request = mozc.sent.last { it.type == Input.CommandType.SET_REQUEST }
        assertEquals(CompositionMode.HALF_ASCII, mozc.sent.last().command.compositionMode)
        assertEquals("QWERTY_MOBILE_TO_HALFWIDTHASCII", request.request.specialRomanjiTable.name)
        assertEquals("A", engine.mode().label.toString())
        engine.setMode(engine.modes()[1])
        assertEquals(CompositionMode.FULL_KATAKANA, mozc.sent.last().command.compositionMode)
        assertEquals("ア", engine.mode().label.toString())
    }

    @Test fun `while loading nothing is sent and keys type as they are`() {
        val mozc = Scripted { reading("に") }
        mozc.state = InputEngine.State.Loading
        mozc.progress = 40
        val engine = MozcEngine(mozc)
        engine.startField(suggestionsAllowed = true, password = false)
        assertEquals("a field starting asks the runtime to load", 1, mozc.starts)
        assertSame(InputEngine.State.Loading, engine.state)
        assertEquals(40, engine.progress)
        assertFalse(engine.onChar('n'.code, null).handled)
        assertFalse(engine.onKey(InputEngine.Key.SPACE).handled)
        assertNull(engine.pick(0))
        assertTrue(mozc.sent.isEmpty())
        var told = 0
        engine.watchState { told++ }
        mozc.becomes(InputEngine.State.Ready)
        assertEquals(1, told)
        assertTrue(engine.onChar('n'.code, null).handled)
    }

    @Test fun `a lost session is made again and the key sent again`() {
        var lost = true
        val mozc = Scripted {
            if (lost) {
                lost = false
                Output.newBuilder().setErrorCode(Output.ErrorCode.SESSION_FAILURE).build()
            } else reading("に")
        }
        val engine = MozcEngine(mozc)
        assertTrue(engine.onChar('n'.code, null).handled)
        assertEquals(2, mozc.sent.count { it.type == Input.CommandType.CREATE_SESSION })
        assertEquals(43L, mozc.sent.last().id)
    }

    @Test fun `password fields are left alone, the field's end saves, and forgetting is passed on`() {
        val mozc = Scripted { reading("に") }
        val engine = MozcEngine(mozc)
        engine.startField(suggestionsAllowed = true, password = true)
        assertFalse(engine.composes())
        engine.startField(suggestionsAllowed = false, password = false)
        assertTrue(engine.composes())
        engine.finishField()
        assertEquals("nothing to save before a session", 0, mozc.syncs)
        engine.onChar('n'.code, null)
        assertTrue(mozc.sent.last().context.suppressSuggestion)
        engine.finishField()
        assertEquals(1, mozc.syncs)
        engine.forgetLearned()
        assertEquals(1, mozc.forgets)
        assertTrue(engine.composing().isEmpty)
    }
}
