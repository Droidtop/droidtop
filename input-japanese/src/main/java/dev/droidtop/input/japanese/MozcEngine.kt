package dev.droidtop.input.japanese

import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.CompositionMode
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.pocketworkstation.pckeyboard.InputEngine

/**
 * Japanese through Google's Mozc, behind the engine seam (Droidtop/tracker#903 H7c; docs/SPEC.md 6a, "The engine
 * seam"). Every call is one Mozc command ([MozcCommands]) evaluated synchronously on the main thread, as SPEC's
 * threading rule says (8 ms a call), and the answer is read back as the seam's composing text and candidates
 * ([MozcOutput]). The library and its dictionary load on [runtime]'s own thread; until they have, [state] says
 * Loading (with the download's [progress]) or Unavailable with the reason, and the keyboard types plain text.
 *
 * One session for the engine's life, made on the first key once ready, and made again if Mozc says it lost it.
 * Pure: the library is behind [Runtime], so the engine is tested on the JVM against a scripted one.
 */
class MozcEngine(private val runtime: Runtime) : InputEngine {
    /** Where Mozc runs: the library, its data and its thread ([MozcRuntime] on a device). */
    interface Runtime {
        val state: InputEngine.State

        /** The dictionary download's percent while loading, -1 when it cannot say. */
        val progress: Int

        /** [onChange] runs on the main thread when [state] or [progress] changes; null stops it. */
        fun watch(onChange: Runnable?)

        /** Loads the library and its data if they are not loaded or loading, on its own thread. */
        fun start()

        /** One command, answered synchronously. Only while [state] is Ready. */
        fun eval(input: Input): Output

        /** SYNC_DATA, on its own thread: Mozc saves what it learned. */
        fun sync()

        /** CLEAR_USER_HISTORY and CLEAR_USER_PREDICTION, on its own thread (or the files, when not loaded). */
        fun forget()
    }

    private val hiragana = InputEngine.Mode("hiragana", "あ")
    private val katakana = InputEngine.Mode("katakana", "ア")
    private val halfWidth = InputEngine.Mode("half-width", "A")
    private val allModes = listOf(hiragana, katakana, halfWidth)

    private var session = 0L
    private var output: Output = Output.getDefaultInstance()
    private var shown = InputEngine.Composing.EMPTY
    private var offered = InputEngine.Candidates.NONE
    private var mode = hiragana
    private var preceding: CharSequence = ""
    private var suggestionsAllowed = true
    private var password = false

    override val capabilities: Set<InputEngine.Capability> = setOf(InputEngine.Capability.CONVERTS, InputEngine.Capability.PREDICTS)

    override val state: InputEngine.State get() = runtime.state

    override val progress: Int get() = runtime.progress

    private val ready: Boolean get() = runtime.state == InputEngine.State.Ready

    override fun watchState(onChange: Runnable?) = runtime.watch(onChange)

    /** Not in a password field; everywhere else, as Mozc's own keyboard composes (its suggestions off where asked). */
    override fun composes(): Boolean = !password

    override fun wantsCandidateBar(): Boolean = true

    override fun startField(suggestionsAllowed: Boolean, password: Boolean) {
        this.suggestionsAllowed = suggestionsAllowed
        this.password = password
        // A load that failed (no connection) is tried again when a field starts.
        runtime.start()
    }

    override fun textBeforeCaret(text: CharSequence) {
        preceding = text.toString()
    }

    override fun finishField() {
        if (ready && session != 0L) runtime.sync()
    }

    override fun forgetLearned() {
        clear()
        runtime.forget()
    }

    override fun onChar(code: Int, nearby: IntArray?): InputEngine.KeyResult {
        if (!ready) return InputEngine.KeyResult.NOT_HANDLED
        return result(send(MozcCommands.char(code)))
    }

    override fun onDelete(): Boolean {
        if (!ready || shown.isEmpty) return false
        send(MozcCommands.backspace())
        return true
    }

    override fun onKey(key: InputEngine.Key): InputEngine.KeyResult {
        if (!ready) return InputEngine.KeyResult.NOT_HANDLED
        return result(send(MozcCommands.key(key)))
    }

    override fun composing(): InputEngine.Composing = shown

    override fun candidates(): InputEngine.Candidates = offered

    override fun pick(index: Int): InputEngine.Picked? {
        if (!ready) return null
        val id = MozcOutput.candidateId(output, index) ?: return null
        val before = preceding
        val answer = send(MozcCommands.submitCandidate(id))
        return InputEngine.Picked(MozcOutput.committed(answer) ?: "", composing = !shown.isEmpty, MozcOutput.deleteBefore(answer, before))
    }

    override fun commit(): CharSequence {
        if (!ready || shown.isEmpty) return ""
        val text = shown.text
        return MozcOutput.committed(send(MozcCommands.submit())) ?: text
    }

    override fun reset() {
        if (ready && session != 0L && (!shown.isEmpty || !offered.isEmpty)) send(MozcCommands.resetContext())
        clear()
    }

    override fun modes(): List<InputEngine.Mode> = allModes

    override fun mode(): InputEngine.Mode = mode

    override fun setMode(mode: InputEngine.Mode) {
        if (!ready || mode !in allModes) return
        val target = compositionMode(mode)
        ensureSession()
        runtime.eval(MozcCommands.setRequest(MozcCommands.request(target)))
        send(MozcCommands.switchMode(target))
        this.mode = mode
    }

    private fun compositionMode(mode: InputEngine.Mode): CompositionMode = when (mode) {
        katakana -> CompositionMode.FULL_KATAKANA
        halfWidth -> CompositionMode.HALF_ASCII
        else -> CompositionMode.HIRAGANA
    }

    private fun clear() {
        output = Output.getDefaultInstance()
        shown = InputEngine.Composing.EMPTY
        offered = InputEngine.Candidates.NONE
    }

    /** What a key did, for the seam: taken when Mozc consumed it, with what it committed and deleted. */
    private fun result(answer: Output): InputEngine.KeyResult {
        if (!answer.consumed) return InputEngine.KeyResult.NOT_HANDLED
        return InputEngine.KeyResult(true, MozcOutput.committed(answer), MozcOutput.deleteBefore(answer, preceding))
    }

    private fun ensureSession() {
        if (session != 0L) return
        val created = runtime.eval(MozcCommands.createSession())
        check(created.hasId() && created.id != 0L) { "Mozc made no session" }
        session = created.id
        runtime.eval(MozcCommands.setRequest(MozcCommands.request(compositionMode(mode))))
        if (mode != hiragana) runtime.eval(withSession(MozcCommands.switchMode(compositionMode(mode))))
    }

    private fun withSession(input: Input): Input = input.toBuilder()
        .setId(session)
        .setContext(MozcCommands.context(preceding, suggestionsAllowed, password))
        .build()

    /**
     * Sends [input] in the session, making one first, and once more in a new session if Mozc lost this one. The answer
     * is what is composed and offered from now on.
     */
    private fun send(input: Input): Output {
        ensureSession()
        var answer = runtime.eval(withSession(input))
        if (answer.errorCode == Output.ErrorCode.SESSION_FAILURE) {
            session = 0L
            ensureSession()
            answer = runtime.eval(withSession(input))
        }
        output = answer
        shown = MozcOutput.composing(answer)
        offered = if (password) InputEngine.Candidates.NONE else MozcOutput.candidates(answer)
        MozcOutput.mode(answer)?.let { reported ->
            mode = when (reported) {
                CompositionMode.FULL_KATAKANA, CompositionMode.HALF_KATAKANA -> katakana
                CompositionMode.HALF_ASCII, CompositionMode.FULL_ASCII -> halfWidth
                CompositionMode.HIRAGANA -> hiragana
                else -> mode
            }
        }
        return answer
    }
}
