package org.pocketworkstation.pckeyboard

/**
 * A conversion engine for tests only (Droidtop/tracker#903 H6): a few romaji to kana, and a tiny kanji table, so the
 * engine seam is proved to carry what Mozc, librime and libhangul need (H7 to H9) without any of them. Letters
 * compose kana; space converts, then cycles the first clause's candidates; enter commits what is shown; a candidate
 * picked commits it with the rest of the clauses. A reading ending in "です" converts as two clauses, the first
 * focused.
 */
class FakeKanaEngine : InputEngine {
    private val romajiToKana = mapOf(
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "ji" to "じ", "de" to "で", "su" to "す", "shi" to "し", "nn" to "ん",
    )

    /** Readings and their candidates, best first, with an annotation for some. */
    private val dictionary = mapOf(
        "かんじ" to listOf(
            "漢字" to "kanji", "感じ" to "feeling", "幹事" to "organiser", "監事" to null, "完治" to null, "寛治" to null,
            "莞爾" to null, "漢辞" to null, "換字" to null, "簡字" to null, "間時" to null, "官事" to null,
        ),
    )

    private var allowed = true
    private val kana = StringBuilder()
    private val romaji = StringBuilder()

    /** While converting: each clause's candidates and its choice; the first clause is the one focused. */
    private var clauses: List<List<InputEngine.Candidate>>? = null
    private var choice = 0

    override val capabilities = setOf(InputEngine.Capability.CONVERTS, InputEngine.Capability.PREDICTS)

    override fun composes() = allowed

    override fun wantsCandidateBar() = allowed

    override fun startField(suggestionsAllowed: Boolean, password: Boolean) {
        allowed = suggestionsAllowed && !password
        reset()
    }

    override fun onChar(code: Int, nearby: IntArray?): Boolean {
        val c = code.toChar().lowercaseChar()
        if (c !in 'a'..'z') return false
        clauses = null
        romaji.append(c)
        while (romaji.isNotEmpty()) {
            val whole = romaji.toString()
            val hit = romajiToKana[whole]
            when {
                hit != null -> {
                    kana.append(hit)
                    romaji.setLength(0)
                }
                romajiToKana.keys.any { it.startsWith(whole) } -> return true
                // "n" before another consonant is ん.
                whole.length > 1 && whole[0] == 'n' -> {
                    kana.append("ん")
                    romaji.deleteCharAt(0)
                }
                else -> {
                    kana.append(whole)
                    romaji.setLength(0)
                }
            }
        }
        return true
    }

    override fun onDelete(): Boolean {
        when {
            clauses != null -> clauses = null
            romaji.isNotEmpty() -> romaji.setLength(romaji.length - 1)
            kana.isNotEmpty() -> kana.setLength(kana.length - 1)
            else -> return false
        }
        return true
    }

    override fun onKey(key: InputEngine.Key): InputEngine.KeyResult {
        if (composing().isEmpty) return InputEngine.KeyResult.NOT_HANDLED
        return when (key) {
            InputEngine.Key.SPACE -> {
                val now = clauses
                if (now == null) convert() else choice = (choice + 1) % now[0].size
                InputEngine.KeyResult.HANDLED
            }
            InputEngine.Key.ENTER -> InputEngine.KeyResult(true, commit())
        }
    }

    private fun convert() {
        flushN()
        val reading = kana.toString()
        val stem = reading.removeSuffix("です")
        val first = dictionary[stem]?.map { (text, note) -> InputEngine.Candidate(text, note) } ?: listOf(InputEngine.Candidate(stem))
        clauses = if (stem != reading) listOf(first, listOf(InputEngine.Candidate("です"))) else listOf(first)
        choice = 0
    }

    private fun flushN() {
        if (romaji.toString() == "n") {
            kana.append("ん")
            romaji.setLength(0)
        }
    }

    override fun composing(): InputEngine.Composing {
        val now = clauses
        if (now == null) {
            val text = kana.toString() + romaji
            return if (text.isEmpty()) InputEngine.Composing.EMPTY else InputEngine.Composing(text, listOf(InputEngine.Segment(0, text.length)))
        }
        val parts = now.mapIndexed { i, list -> (if (i == 0) list[choice] else list[0]).text.toString() }
        val segments = ArrayList<InputEngine.Segment>()
        var at = 0
        for (part in parts) {
            segments += InputEngine.Segment(at, at + part.length)
            at += part.length
        }
        return InputEngine.Composing(parts.joinToString(""), segments, focused = 0)
    }

    override fun candidates(): InputEngine.Candidates {
        val now = clauses
        if (now != null) return InputEngine.Candidates(now[0], focused = choice, pageSize = 9)
        if (kana.isEmpty()) return InputEngine.Candidates.NONE
        val reading = kana.toString()
        val katakana = reading.map { if (it in 'ぁ'..'ゖ') it + 0x60 else it }.joinToString("")
        return InputEngine.Candidates(listOf(InputEngine.Candidate(reading), InputEngine.Candidate(katakana, "katakana")))
    }

    override fun pick(index: Int): CharSequence? {
        val now = clauses
        val text = if (now != null) {
            val first = now[0].getOrNull(index) ?: return null
            first.text.toString() + now.drop(1).joinToString("") { it[0].text }
        } else {
            candidates().items.getOrNull(index)?.text?.toString() ?: return null
        }
        reset()
        return text
    }

    override fun commit(): CharSequence {
        flushN()
        val text = composing().text.toString()
        reset()
        return text
    }

    override fun reset() {
        kana.setLength(0)
        romaji.setLength(0)
        clauses = null
        choice = 0
    }
}
