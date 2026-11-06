package dev.droidtop.input.chinese

import org.pocketworkstation.pckeyboard.InputEngine

/**
 * Chinese through RIME (librime), behind the engine seam (Droidtop/tracker#903 H8b; docs/SPEC.md 6a, "Chinese through
 * RIME"). Every call is one or two librime calls through droidtop's JNI, made synchronously on the main thread under
 * SPEC's threading rule, and the session's context is read back as the seam's composing text and candidates
 * ([RimeContext]). The library and its data load on [runtime]'s own thread; until they have, [state] says Loading (with
 * the download's [progress]) or Unavailable with the reason, and the keyboard types plain text.
 *
 * One librime session for the engine's life, made on the first key once ready with [choice]'s schema, and made again
 * if librime no longer has it (after "Forget what it learned" restarts librime). Pure: the library is behind
 * [Runtime], so the engine is tested on the JVM against a scripted one.
 */
class RimeEngine(private val runtime: Runtime, private val choice: RimeSchemas.Choice) : InputEngine {
    /** Where librime runs: the library, its data and its thread ([RimeRuntime] on a device). */
    interface Runtime {
        val state: InputEngine.State

        /** The data download's percent while loading, -1 when it cannot say. */
        val progress: Int

        /** [onChange] runs on the main thread when [state] or [progress] changes; null stops it. */
        fun watch(onChange: Runnable?)

        /** Loads the library and its data if they are not loaded or loading, on its own thread. */
        fun start()

        // The session calls below are synchronous, and only while [state] is Ready.

        /** A new session; 0 when librime made none. */
        fun createSession(): Long

        /** Whether librime still has [session]. */
        fun hasSession(session: Long): Boolean

        fun selectSchema(session: Long, schema: String): Boolean

        fun setOption(session: Long, option: String, value: Boolean)

        /** A key: whether librime took it. */
        fun processKey(session: Long, keysym: Int, mask: Int): Boolean

        /** What librime committed since it was last asked, or null. */
        fun takeCommit(session: Long): String?

        /** What the session shows now. */
        fun context(session: Long): RimeContext

        /** Candidate [index], counted across librime's pages. */
        fun selectCandidate(session: Long, index: Int): Boolean

        /** Drops what is composed. */
        fun clear(session: Long)

        /** Forgets what librime learned, on its own thread. */
        fun forget()
    }

    private val simplified = InputEngine.Mode("simplified", "简")
    private val traditional = InputEngine.Mode("traditional", "繁")
    private val ascii = InputEngine.Mode("ascii", "A")
    private val allModes = if (choice.simplifiedFirst) listOf(simplified, traditional, ascii) else listOf(traditional, simplified, ascii)

    private var session = 0L
    private var shown = InputEngine.Composing.EMPTY
    private var offered = InputEngine.Candidates.NONE
    private var mode = allModes[0]

    /** The Chinese forms last chosen, kept while letters pass through (A) and back. */
    private var simplifiedForms = choice.simplifiedFirst
    private var password = false

    override val capabilities: Set<InputEngine.Capability> = setOf(InputEngine.Capability.CONVERTS)

    override val state: InputEngine.State get() = runtime.state

    override val progress: Int get() = runtime.progress

    private val ready: Boolean get() = runtime.state == InputEngine.State.Ready

    override fun watchState(onChange: Runnable?) = runtime.watch(onChange)

    /** Not in a password field; everywhere else, as Mozc composes. */
    override fun composes(): Boolean = !password

    override fun wantsCandidateBar(): Boolean = true

    override fun startField(suggestionsAllowed: Boolean, password: Boolean) {
        this.password = password
        // A load that failed (no connection) is tried again when a field starts.
        runtime.start()
    }

    // textBeforeCaret: luna_pinyin converts from what is typed alone, so the text before the caret is not used.

    // finishField: librime writes what it learns to its user dictionary as it goes, so there is nothing to save.

    override fun forgetLearned() {
        clear()
        runtime.forget()
    }

    override fun onChar(code: Int, nearby: IntArray?): InputEngine.KeyResult {
        if (!ready) return InputEngine.KeyResult.NOT_HANDLED
        val keysym = RimeKeys.forChar(code) ?: return InputEngine.KeyResult.NOT_HANDLED
        return press(RimeKeys.Press(keysym), String(Character.toChars(code)), composingOnly = false)
    }

    override fun onDelete(): Boolean {
        if (!ready || shown.isEmpty || !ensureSession()) return false
        runtime.processKey(session, RimeKeys.BACKSPACE, 0)
        runtime.takeCommit(session)
        read()
        return true
    }

    override fun onKey(key: InputEngine.Key): InputEngine.KeyResult {
        if (!ready) return InputEngine.KeyResult.NOT_HANDLED
        val press = RimeKeys.forKey(key) ?: return InputEngine.KeyResult.NOT_HANDLED
        val composingOnly = key != InputEngine.Key.SPACE && key != InputEngine.Key.ENTER
        return press(press, null, composingOnly)
    }

    /**
     * A key to librime and what it did, for the seam. Taken when librime took it. When it did not but committed what was
     * composed (a character it passes on), the commit and the character go together. A moving key librime ignored while
     * something is composed is still the engine's, so the field's caret does not move under the composing text.
     */
    private fun press(press: RimeKeys.Press, typed: String?, composingOnly: Boolean): InputEngine.KeyResult {
        val wasComposing = !shown.isEmpty
        if (!ensureSession()) return InputEngine.KeyResult.NOT_HANDLED
        val taken = runtime.processKey(session, press.keysym, press.mask)
        val committed = runtime.takeCommit(session)
        read()
        return when {
            taken -> InputEngine.KeyResult(true, committed)
            committed != null -> InputEngine.KeyResult(true, committed + (typed ?: ""))
            composingOnly && wasComposing -> InputEngine.KeyResult.HANDLED
            else -> InputEngine.KeyResult.NOT_HANDLED
        }
    }

    override fun composing(): InputEngine.Composing = shown

    override fun candidates(): InputEngine.Candidates = offered

    override fun pick(index: Int): InputEngine.Picked? {
        if (!ready || index !in offered.items.indices || !ensureSession()) return null
        if (!runtime.selectCandidate(session, index)) return null
        val committed = runtime.takeCommit(session)
        read()
        return InputEngine.Picked(committed ?: "", composing = !shown.isEmpty)
    }

    /**
     * What is composed, as it stands: what Enter commits in luna_pinyin, the parts already picked and the rest as typed
     * (librime's express_editor). The preedit as shown if librime commits nothing.
     */
    override fun commit(): CharSequence {
        if (!ready || shown.isEmpty) return ""
        val text = shown.text
        if (!ensureSession()) {
            clear()
            return text
        }
        runtime.processKey(session, RimeKeys.RETURN, 0)
        val committed = runtime.takeCommit(session)
        runtime.clear(session)
        clear()
        return committed ?: text
    }

    override fun reset() {
        if (ready && session != 0L && (!shown.isEmpty || !offered.isEmpty)) runtime.clear(session)
        clear()
    }

    override fun modes(): List<InputEngine.Mode> = allModes

    override fun mode(): InputEngine.Mode = mode

    /**
     * 简 and 繁 set the output forms (librime's zh_hans, or the schema's traditional option); A sets ascii_mode, so
     * letters pass through. librime cannot hand back what was composed when it switches to A, and the seam has no way to
     * put it in the field from here, so it is dropped; 简 and 繁 keep it and convert it again.
     */
    override fun setMode(mode: InputEngine.Mode) {
        if (!ready || mode !in allModes || !ensureSession()) return
        if (mode == ascii && !shown.isEmpty) runtime.clear(session)
        this.mode = mode
        if (mode != ascii) simplifiedForms = mode == simplified
        applyMode()
        read()
    }

    private fun applyMode() {
        runtime.setOption(session, RimeSchemas.ASCII_MODE, mode == ascii)
        for ((option, on) in RimeSchemas.formOptions(choice, simplifiedForms)) runtime.setOption(session, option, on)
    }

    private fun clear() {
        shown = InputEngine.Composing.EMPTY
        offered = InputEngine.Candidates.NONE
    }

    /**
     * A session with [choice]'s schema and the mode applied, made when there is none or librime lost it. False when
     * librime made none (it is restarting): the key is then the keyboard's.
     */
    private fun ensureSession(): Boolean {
        if (session != 0L && runtime.hasSession(session)) return true
        session = runtime.createSession()
        if (session == 0L) return false
        if (!runtime.selectSchema(session, choice.schema)) {
            session = 0L
            return false
        }
        applyMode()
        return true
    }

    /** The session's context, read back: what is composed and offered from now on, and the mode if librime changed it. */
    private fun read() {
        val context = runtime.context(session)
        shown = context.composing()
        offered = if (password) InputEngine.Candidates.NONE else context.candidates()
        if (context.asciiMode != (mode == ascii)) mode = if (context.asciiMode) ascii else if (simplifiedForms) simplified else traditional
    }
}
