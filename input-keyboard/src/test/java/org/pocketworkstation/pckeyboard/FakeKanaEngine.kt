package org.pocketworkstation.pckeyboard

/**
 * A conversion engine for tests only (Droidtop/tracker#903 H6, extended in H7a): a few romaji to kana, and a tiny kanji
 * table, so the engine seam is proved to carry what Mozc, librime and libhangul need (H7 to H9) without any of them.
 *
 * - Letters compose kana at the caret; Left and Right move the caret. Like Mozc, it counts the reading in code points
 *   ("q" types 𠮷, "x" types 😀, each two UTF-16 chars) and gives the seam UTF-16 positions through [CodePoints].
 * - "7" is a multi-tap key: ま, then み, む, め, も while it is pressed again, until [InputEngine.Key.TOGGLE].
 * - Space converts. A reading ending in "です" converts as two clauses, the first focused. Space and Down then cycle the
 *   focused clause's candidates, Up goes back, Left and Right move the focus, Shift-Left and Shift-Right make the
 *   focused clause shorter or longer, Escape goes back to the reading.
 * - Enter commits what is shown. A candidate picked on the first clause commits that clause and leaves the rest
 *   composing (a partial commit); picked on another, it only chooses.
 * - With nothing composed, after "字" it suggests "です" and "を" (zero-query), from the text before the caret it is
 *   given. Undo takes the last commit back into composing, asking the field to delete it.
 * - Its modes are hiragana, katakana and half-width letters. It learns the candidate last picked for a reading,
 *   offering it first after; [forgetLearned] drops that. It counts [finishField] calls.
 * - Its [state] (and its loading [progress]) is set by the test, and a change is reported through [watchState] as an
 *   engine would.
 */
class FakeKanaEngine(state: InputEngine.State = InputEngine.State.Ready) : InputEngine {
    private val romajiToKana = mapOf(
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "ji" to "じ", "de" to "で", "su" to "す", "shi" to "し", "nn" to "ん",
        "q" to "𠮷", "x" to "😀",
    )

    /** Readings and their candidates, best first, with an annotation for some. */
    private val dictionary = mapOf(
        "かんじ" to listOf(
            "漢字" to "kanji", "感じ" to "feeling", "幹事" to "organiser", "監事" to null, "完治" to null, "寛治" to null,
            "莞爾" to null, "漢辞" to null, "換字" to null, "簡字" to null, "間時" to null, "官事" to null,
        ),
    )

    private val zeroQuery = mapOf('字' to listOf("です", "を"))

    private val toggleKana = listOf("ま", "み", "む", "め", "も")

    val hiragana = InputEngine.Mode("hiragana", "あ")
    val katakana = InputEngine.Mode("katakana", "ア")
    val halfWidth = InputEngine.Mode("half-width", "A")

    private var current = state
    private var watcher: Runnable? = null

    var finishedFields = 0
        private set

    /** The last text before the caret the host gave it. */
    var context: String = ""
        private set

    private var allowed = true

    /** The reading, one code point to an entry, and the caret among them. */
    private val kana = ArrayList<String>()
    private var caret = 0
    private val romaji = StringBuilder()
    private var toggleStep = -1

    private class Clause(var reading: String, var choice: Int = 0)

    /** While converting: the clauses and the focused one. */
    private var clauses: MutableList<Clause>? = null
    private var focus = 0

    private var suggestions: List<InputEngine.Candidate> = emptyList()
    private var lastCommit: Pair<String, String>? = null
    private val learned = HashMap<String, String>()
    private var mode = hiragana

    override val capabilities = setOf(InputEngine.Capability.CONVERTS, InputEngine.Capability.PREDICTS)

    override val state: InputEngine.State get() = current

    override fun watchState(onChange: Runnable?) {
        watcher = onChange
    }

    /** The engine's data finished loading, or went missing. */
    fun becomes(state: InputEngine.State, percent: Int = -1) {
        current = state
        loaded = percent
        watcher?.run()
    }

    private var loaded = -1

    override val progress: Int get() = loaded

    override fun composes() = allowed

    override fun wantsCandidateBar() = allowed

    override fun startField(suggestionsAllowed: Boolean, password: Boolean) {
        allowed = suggestionsAllowed && !password
        reset()
    }

    override fun textBeforeCaret(text: CharSequence) {
        context = text.toString()
        suggestions = if (composing().isEmpty) {
            zeroQuery[context.lastOrNull()].orEmpty().map { InputEngine.Candidate(it) }
        } else {
            emptyList()
        }
    }

    override fun finishField() {
        finishedFields++
    }

    override fun forgetLearned() {
        learned.clear()
    }

    override fun modes() = listOf(hiragana, katakana, halfWidth)

    override fun mode() = mode

    override fun setMode(mode: InputEngine.Mode) {
        this.mode = mode
    }

    override fun onChar(code: Int, nearby: IntArray?): InputEngine.KeyResult {
        val c = code.toChar().lowercaseChar()
        if (c == '7') return toggle()
        if (c !in 'a'..'z') return InputEngine.KeyResult.NOT_HANDLED
        toggleStep = -1
        // Typing after a conversion commits it first, as Mozc does: a result and a new reading in one answer.
        val committed = if (clauses != null) commitAll() else null
        suggestions = emptyList()
        if (mode == halfWidth) {
            insert(code.toChar().toString())
            return InputEngine.KeyResult(true, committed)
        }
        romaji.append(c)
        while (romaji.isNotEmpty()) {
            val whole = romaji.toString()
            val hit = romajiToKana[whole]
            when {
                hit != null -> {
                    insert(hit)
                    romaji.setLength(0)
                }
                romajiToKana.keys.any { it.startsWith(whole) } -> return InputEngine.KeyResult(true, committed)
                // "n" before another consonant is ん.
                whole.length > 1 && whole[0] == 'n' -> {
                    insert("ん")
                    romaji.deleteCharAt(0)
                }
                else -> {
                    whole.forEach { insert(it.toString()) }
                    romaji.setLength(0)
                }
            }
        }
        return InputEngine.KeyResult(true, committed)
    }

    private fun toggle(): InputEngine.KeyResult {
        if (clauses != null) return InputEngine.KeyResult.NOT_HANDLED
        if (toggleStep >= 0 && caret > 0) {
            toggleStep = (toggleStep + 1) % toggleKana.size
            kana[caret - 1] = inMode(toggleKana[toggleStep])
        } else {
            toggleStep = 0
            insert(toggleKana[0])
        }
        return InputEngine.KeyResult.HANDLED
    }

    private fun inMode(text: String) = if (mode == katakana) toKatakana(text) else text

    private fun toKatakana(text: String) = text.map { if (it in 'ぁ'..'ゖ') it + 0x60 else it }.joinToString("")

    /** Inserts [text]'s code points at the caret. */
    private fun insert(text: String) {
        val inMode = inMode(text)
        var at = 0
        while (at < inMode.length) {
            val next = inMode.offsetByCodePoints(at, 1)
            kana.add(caret++, inMode.substring(at, next))
            at = next
        }
    }

    override fun onDelete(): Boolean {
        toggleStep = -1
        when {
            clauses != null -> clauses = null
            romaji.isNotEmpty() -> romaji.setLength(romaji.length - 1)
            caret > 0 -> kana.removeAt(--caret)
            else -> return false
        }
        return true
    }

    override fun onKey(key: InputEngine.Key): InputEngine.KeyResult {
        if (composing().isEmpty) return whenEmpty(key)
        if (key != InputEngine.Key.TOGGLE) toggleStep = -1
        val now = clauses ?: return reading(key)
        val clause = now[focus]
        when (key) {
            InputEngine.Key.SPACE, InputEngine.Key.DOWN -> clause.choice = (clause.choice + 1) % candidatesOf(clause.reading).size
            InputEngine.Key.UP -> clause.choice = (clause.choice + candidatesOf(clause.reading).size - 1) % candidatesOf(clause.reading).size
            InputEngine.Key.ENTER -> return InputEngine.KeyResult(true, commitAll())
            InputEngine.Key.LEFT -> focus = maxOf(0, focus - 1)
            InputEngine.Key.RIGHT -> focus = minOf(now.size - 1, focus + 1)
            InputEngine.Key.SHIFT_LEFT -> shrink(now)
            InputEngine.Key.SHIFT_RIGHT -> grow(now)
            InputEngine.Key.ESCAPE -> {
                clauses = null
                caret = kana.size
            }
            InputEngine.Key.UNDO -> return InputEngine.KeyResult.NOT_HANDLED
            InputEngine.Key.TOGGLE -> Unit
        }
        return InputEngine.KeyResult.HANDLED
    }

    /** The focused clause one code point shorter: its last goes to the start of the next, which is made if need be. */
    private fun shrink(now: MutableList<Clause>) {
        val clause = now[focus]
        val cps = clause.reading.codePointCount(0, clause.reading.length)
        if (cps < 2) return
        val cut = clause.reading.offsetByCodePoints(0, cps - 1)
        val moved = clause.reading.substring(cut)
        clause.reading = clause.reading.substring(0, cut)
        clause.choice = 0
        if (focus + 1 < now.size) {
            now[focus + 1].reading = moved + now[focus + 1].reading
            now[focus + 1].choice = 0
        } else {
            now += Clause(moved)
        }
    }

    /** The focused clause one code point longer, from the start of the next; a next left empty goes. */
    private fun grow(now: MutableList<Clause>) {
        if (focus + 1 >= now.size) return
        val next = now[focus + 1]
        val cut = next.reading.offsetByCodePoints(0, 1)
        now[focus].reading += next.reading.substring(0, cut)
        now[focus].choice = 0
        next.reading = next.reading.substring(cut)
        next.choice = 0
        if (next.reading.isEmpty()) now.removeAt(focus + 1)
    }

    private fun reading(key: InputEngine.Key): InputEngine.KeyResult {
        when (key) {
            InputEngine.Key.SPACE -> convert()
            InputEngine.Key.ENTER -> return InputEngine.KeyResult(true, commit())
            InputEngine.Key.LEFT, InputEngine.Key.SHIFT_LEFT -> {
                flushRomaji()
                caret = maxOf(0, caret - 1)
            }
            InputEngine.Key.RIGHT, InputEngine.Key.SHIFT_RIGHT -> {
                flushRomaji()
                caret = minOf(kana.size, caret + 1)
            }
            InputEngine.Key.ESCAPE -> reset()
            InputEngine.Key.TOGGLE -> toggleStep = -1
            InputEngine.Key.UP, InputEngine.Key.DOWN -> Unit
            InputEngine.Key.UNDO -> return InputEngine.KeyResult.NOT_HANDLED
        }
        return InputEngine.KeyResult.HANDLED
    }

    /** Nothing composed: undo takes the last commit back; every other key is the keyboard's. */
    private fun whenEmpty(key: InputEngine.Key): InputEngine.KeyResult {
        val (text, reading) = lastCommit ?: return InputEngine.KeyResult.NOT_HANDLED
        if (key != InputEngine.Key.UNDO || !context.endsWith(text)) return InputEngine.KeyResult.NOT_HANDLED
        // The field is asked to delete the commit, counted as Mozc counts (code points), given in UTF-16 chars.
        val delete = CodePoints.utf16Before(context, text.codePointCount(0, text.length))
        lastCommit = null
        suggestions = emptyList()
        reading.codePoints().forEach { kana.add(String(Character.toChars(it))) }
        caret = kana.size
        return InputEngine.KeyResult(true, deleteBefore = delete)
    }

    private fun flushRomaji() {
        if (romaji.toString() == "n") insert("ん")
        romaji.setLength(0)
    }

    private fun readingText() = kana.joinToString("")

    private fun candidatesOf(reading: String): List<InputEngine.Candidate> {
        val listed = dictionary[reading]?.map { (text, note) -> InputEngine.Candidate(text, note) }
            ?: listOf(InputEngine.Candidate(reading), InputEngine.Candidate(toKatakana(reading), "katakana"))
        val first = learned[reading] ?: return listed
        return listed.sortedBy { if (it.text == first) 0 else 1 }
    }

    private fun convert() {
        flushRomaji()
        val reading = readingText()
        val stem = reading.removeSuffix("です")
        clauses = if (stem != reading && stem.isNotEmpty()) mutableListOf(Clause(stem), Clause("です")) else mutableListOf(Clause(reading))
        focus = 0
    }

    private fun clauseText(clause: Clause) = candidatesOf(clause.reading)[clause.choice].text.toString()

    /** Commits every clause as chosen; learns each choice. */
    private fun commitAll(): String {
        val now = clauses.orEmpty()
        now.forEach { learned[it.reading] = clauseText(it) }
        val text = now.joinToString("") { clauseText(it) }
        remember(text, now.joinToString("") { it.reading })
        clearComposing()
        return text
    }

    private fun remember(text: String, reading: String) {
        lastCommit = text to reading
    }

    override fun composing(): InputEngine.Composing {
        val now = clauses
        if (now == null) {
            val before = kana.subList(0, caret).joinToString("") + romaji
            val text = before + kana.subList(caret, kana.size).joinToString("")
            if (text.isEmpty()) return InputEngine.Composing.EMPTY
            val cps = text.codePointCount(0, text.length)
            return InputEngine.Composing(
                text,
                CodePoints.segments(text, intArrayOf(cps)),
                cursor = CodePoints.toUtf16(text, before.codePointCount(0, before.length)),
            )
        }
        val parts = now.map { clauseText(it) }
        val text = parts.joinToString("")
        return InputEngine.Composing(text, CodePoints.segments(text, parts.map { it.codePointCount(0, it.length) }.toIntArray()), focused = focus)
    }

    override fun candidates(): InputEngine.Candidates {
        val now = clauses
        if (now != null) return InputEngine.Candidates(candidatesOf(now[focus].reading), focused = now[focus].choice, pageSize = 9)
        if (kana.isEmpty()) return InputEngine.Candidates(suggestions)
        return InputEngine.Candidates(candidatesOf(readingText()))
    }

    override fun pick(index: Int): InputEngine.Picked? {
        val now = clauses
        if (now != null) {
            val clause = now[focus]
            if (index !in candidatesOf(clause.reading).indices) return null
            clause.choice = index
            if (focus > 0) return InputEngine.Picked("", composing = true)
            // The first clause goes into the field; the rest stay converting.
            val text = clauseText(clause)
            learned[clause.reading] = text
            remember(text, clause.reading)
            now.removeAt(0)
            if (now.isNotEmpty()) {
                kana.clear()
                now.forEach { c -> c.reading.codePoints().forEach { kana.add(String(Character.toChars(it))) } }
                caret = kana.size
                return InputEngine.Picked(text, composing = true)
            }
            clearComposing()
            return InputEngine.Picked(text)
        }
        val text = candidates().items.getOrNull(index)?.text?.toString() ?: return null
        if (kana.isNotEmpty()) remember(text, readingText())
        clearComposing()
        suggestions = emptyList()
        return InputEngine.Picked(text)
    }

    override fun commit(): CharSequence {
        if (clauses != null) return commitAll()
        flushRomaji()
        val text = composing().text.toString()
        if (text.isNotEmpty()) remember(text, text)
        clearComposing()
        return text
    }

    private fun clearComposing() {
        kana.clear()
        caret = 0
        romaji.setLength(0)
        clauses = null
        focus = 0
        toggleStep = -1
    }

    override fun reset() {
        clearComposing()
        suggestions = emptyList()
    }
}
