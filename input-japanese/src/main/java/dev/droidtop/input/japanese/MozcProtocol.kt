package dev.droidtop.input.japanese

import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.Annotation
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Capability
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.CompositionMode
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Preedit
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Request
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import org.pocketworkstation.pckeyboard.CodePoints
import org.pocketworkstation.pckeyboard.InputEngine

/**
 * The engine seam in Mozc's words (Droidtop/tracker#903 H7c; docs/SPEC.md 6a, "The engine seam", whose H7a list maps
 * each part of the seam to protocol/commands.proto): what [MozcEngine] sends ([MozcCommands]) and how it reads the
 * answer ([MozcOutput]). Pure: the generated protobuf lite classes and the seam's types only.
 */
object MozcCommands {
    /**
     * A new session. It may delete text before the caret (Output.deletion_range), which Undo after a commit needs:
     * the seam's deleteBefore.
     */
    @JvmStatic
    fun createSession(): Input = Input.newBuilder()
        .setType(Input.CommandType.CREATE_SESSION)
        .setCapability(Capability.newBuilder().setTextDeletion(Capability.TextDeletionCapabilityType.DELETE_PRECEDING_TEXT))
        .build()

    /**
     * What a soft keyboard asks of Mozc, as Mozc's own Android client asked it (MozcUtil.setSoftwareKeyboardRequest
     * and the QWERTY keyboards' specifications, google/mozc at afb03ddf): mixed conversion (predictions and conversions
     * in one list, all_candidate_words), zero-query suggestions after a commit, a partial commit of the first clause
     * offered by itself, and the mode not guessed from the field's text. Romaji from a QWERTY layout: kana modes use
     * QWERTY_MOBILE_TO_HIRAGANA, where space converts and keeps composing; half-width letters use
     * QWERTY_MOBILE_TO_HALFWIDTHASCII, where space commits, and moving past the composition's edge commits it.
     */
    @JvmStatic
    fun request(mode: CompositionMode): Request {
        val letters = mode == CompositionMode.HALF_ASCII || mode == CompositionMode.FULL_ASCII
        return Request.newBuilder()
            .setMixedConversion(true)
            .setZeroQuerySuggestion(true)
            .setUpdateInputModeFromSurroundingText(false)
            .setAutoPartialSuggestion(true)
            .setKanaModifierInsensitiveConversion(false)
            .setSpecialRomanjiTable(
                if (letters) Request.SpecialRomanjiTable.QWERTY_MOBILE_TO_HALFWIDTHASCII
                else Request.SpecialRomanjiTable.QWERTY_MOBILE_TO_HIRAGANA,
            )
            .setSpaceOnAlphanumeric(
                if (letters) Request.SpaceOnAlphanumeric.COMMIT
                else Request.SpaceOnAlphanumeric.SPACE_OR_CONVERT_KEEPING_COMPOSITION,
            )
            .setCrossingEdgeBehavior(
                if (letters) Request.CrossingEdgeBehavior.COMMIT_WITHOUT_CONSUMING
                else Request.CrossingEdgeBehavior.DO_NOTHING,
            )
            .build()
    }

    /** SET_REQUEST: Mozc applies a request to every session. */
    @JvmStatic
    fun setRequest(request: Request): Input = Input.newBuilder().setType(Input.CommandType.SET_REQUEST).setRequest(request).build()

    /**
     * What the field says, sent with every key and command (Mozc clears it when a command has none): up to 20 chars
     * before the caret (Context.preceding_text, the seam's textBeforeCaret), no suggestions where the field asks for
     * none (passwords, addresses), and whether it is a password.
     */
    @JvmStatic
    fun context(precedingText: CharSequence, suggestionsAllowed: Boolean, password: Boolean): Context {
        val builder = Context.newBuilder().setPrecedingText(precedingText.toString())
        if (!suggestionsAllowed) builder.setSuppressSuggestion(true)
        if (password) builder.setInputFieldType(Context.InputFieldType.PASSWORD)
        return builder.build()
    }

    /** A character typed: SEND_KEY with its code point (Mozc's key_code is a code point, not only ASCII). */
    @JvmStatic
    fun char(code: Int): Input = sendKey(KeyEvent.newBuilder().setKeyCode(code).build())

    /** Backspace, inside what is composed. */
    @JvmStatic
    fun backspace(): Input = sendKey(special(KeyEvent.SpecialKey.BACKSPACE))

    /**
     * An engine key. Mozc's mobile keymap (data/keymap/mobile.tsv) gives them their meaning by state: Space converts,
     * then picks the next candidate; Enter commits; Left and Right move the caret, or the focused clause while
     * converting; Shift with them makes the focused clause one shorter or longer; Up and Down step its candidates;
     * Escape cancels. Undo is SessionCommand UNDO_OR_REWIND (the last commit back into composing), Toggle
     * STOP_KEY_TOGGLING (a multi-tap key's cycling ends).
     */
    @JvmStatic
    fun key(key: InputEngine.Key): Input = when (key) {
        InputEngine.Key.SPACE -> sendKey(special(KeyEvent.SpecialKey.SPACE))
        InputEngine.Key.ENTER -> sendKey(special(KeyEvent.SpecialKey.ENTER))
        InputEngine.Key.LEFT -> sendKey(special(KeyEvent.SpecialKey.LEFT))
        InputEngine.Key.RIGHT -> sendKey(special(KeyEvent.SpecialKey.RIGHT))
        InputEngine.Key.UP -> sendKey(special(KeyEvent.SpecialKey.UP))
        InputEngine.Key.DOWN -> sendKey(special(KeyEvent.SpecialKey.DOWN))
        InputEngine.Key.SHIFT_LEFT -> sendKey(special(KeyEvent.SpecialKey.LEFT, shift = true))
        InputEngine.Key.SHIFT_RIGHT -> sendKey(special(KeyEvent.SpecialKey.RIGHT, shift = true))
        InputEngine.Key.ESCAPE -> sendKey(special(KeyEvent.SpecialKey.ESCAPE))
        InputEngine.Key.UNDO -> command(SessionCommand.CommandType.UNDO_OR_REWIND)
        InputEngine.Key.TOGGLE -> command(SessionCommand.CommandType.STOP_KEY_TOGGLING)
    }

    /** A candidate picked: SUBMIT_CANDIDATE, Mozc's mobile pick, which commits it (and only the clause it covers). */
    @JvmStatic
    fun submitCandidate(id: Int): Input = sendCommand(SessionCommand.newBuilder().setType(SessionCommand.CommandType.SUBMIT_CANDIDATE).setId(id).build())

    /** Commit what is composed as it stands. */
    @JvmStatic
    fun submit(): Input = command(SessionCommand.CommandType.SUBMIT)

    /** Drop what is composed and the suggestions, and forget what came before (the field changed under it). */
    @JvmStatic
    fun resetContext(): Input = command(SessionCommand.CommandType.RESET_CONTEXT)

    @JvmStatic
    fun switchMode(mode: CompositionMode): Input = sendCommand(
        SessionCommand.newBuilder().setType(SessionCommand.CommandType.SWITCH_COMPOSITION_MODE).setCompositionMode(mode).build(),
    )

    /** The field ended: Mozc saves what it learned. */
    @JvmStatic
    fun syncData(): Input = Input.newBuilder().setType(Input.CommandType.SYNC_DATA).build()

    /** "Forget what it learned": the conversion history, then the predictions. */
    @JvmStatic
    fun forget(): List<Input> = listOf(
        Input.newBuilder().setType(Input.CommandType.CLEAR_USER_HISTORY).build(),
        Input.newBuilder().setType(Input.CommandType.CLEAR_USER_PREDICTION).build(),
    )

    private fun special(key: KeyEvent.SpecialKey, shift: Boolean = false): KeyEvent {
        val builder = KeyEvent.newBuilder().setSpecialKey(key)
        if (shift) builder.addModifierKeys(KeyEvent.ModifierKey.SHIFT)
        return builder.build()
    }

    private fun sendKey(key: KeyEvent): Input = Input.newBuilder().setType(Input.CommandType.SEND_KEY).setKey(key).build()

    private fun command(type: SessionCommand.CommandType): Input = sendCommand(SessionCommand.newBuilder().setType(type).build())

    private fun sendCommand(command: SessionCommand): Input =
        Input.newBuilder().setType(Input.CommandType.SEND_COMMAND).setCommand(command).build()
}

/**
 * Mozc's answer as the seam's types (Droidtop/tracker#903 H7c). Mozc counts code points (Preedit.cursor,
 * Segment.value_length, highlighted_position, DeletionRange); the seam counts UTF-16 chars, so every position goes
 * through [CodePoints]. Pure.
 */
object MozcOutput {
    /**
     * The composing text: Preedit's segments laid end to end, each a clause; the focused one is where
     * highlighted_position starts (Mozc sets it to the focused clause's start while converting, else the one marked
     * HIGHLIGHT); the caret is Preedit.cursor.
     */
    @JvmStatic
    fun composing(output: Output): InputEngine.Composing {
        if (!output.hasPreedit()) return InputEngine.Composing.EMPTY
        val preedit = output.preedit
        val text = StringBuilder()
        val lengths = IntArray(preedit.segmentCount)
        val starts = IntArray(preedit.segmentCount)
        var cp = 0
        for (i in 0 until preedit.segmentCount) {
            val segment = preedit.getSegment(i)
            text.append(segment.value)
            val length = if (segment.hasValueLength()) segment.valueLength else segment.value.codePointCount(0, segment.value.length)
            lengths[i] = length
            starts[i] = cp
            cp += length
        }
        if (text.isEmpty()) return InputEngine.Composing.EMPTY
        val segments = CodePoints.segments(text, lengths)
        var focused = -1
        if (preedit.hasHighlightedPosition()) focused = starts.indexOf(preedit.highlightedPosition)
        if (focused < 0) {
            focused = (0 until preedit.segmentCount).firstOrNull {
                preedit.getSegment(it).annotation == Preedit.Segment.Annotation.HIGHLIGHT
            } ?: -1
        }
        val cursor = CodePoints.toUtf16(text, preedit.cursor)
        return InputEngine.Composing(text.toString(), if (segments.size > 1 || focused >= 0) segments else emptyList(), focused, cursor)
    }

    /**
     * The candidates: all_candidate_words, the mobile list (conversions and predictions together, and zero-query
     * suggestions with nothing composed), with candidate_window's page as a fallback. Each annotation is its prefix,
     * suffix and description, as given.
     */
    @JvmStatic
    fun candidates(output: Output): InputEngine.Candidates {
        if (output.hasAllCandidateWords() && output.allCandidateWords.candidatesCount > 0) {
            val list = output.allCandidateWords
            val items = list.candidatesList.map { InputEngine.Candidate(it.value, annotation(if (it.hasAnnotation()) it.annotation else null)) }
            return InputEngine.Candidates(items, if (list.hasFocusedIndex()) list.focusedIndex else -1)
        }
        if (output.hasCandidateWindow() && output.candidateWindow.candidateCount > 0) {
            val window = output.candidateWindow
            val items = window.candidateList.map { InputEngine.Candidate(it.value, annotation(if (it.hasAnnotation()) it.annotation else null)) }
            val focused = if (window.hasFocusedIndex()) window.candidateList.indexOfFirst { it.index == window.focusedIndex } else -1
            return InputEngine.Candidates(items, focused)
        }
        return InputEngine.Candidates.NONE
    }

    /** The id Mozc gave the candidate at [index] in [candidates]' order, for SUBMIT_CANDIDATE; null when there is none. */
    @JvmStatic
    fun candidateId(output: Output, index: Int): Int? {
        if (output.hasAllCandidateWords() && output.allCandidateWords.candidatesCount > 0) {
            val list = output.allCandidateWords.candidatesList
            return list.getOrNull(index)?.takeIf { it.hasId() }?.id
        }
        if (output.hasCandidateWindow()) {
            return output.candidateWindow.candidateList.getOrNull(index)?.takeIf { it.hasId() }?.id
        }
        return null
    }

    /** What went into the field: Output.result's value, null for none. */
    @JvmStatic
    fun committed(output: Output): String? =
        if (output.hasResult() && output.result.value.isNotEmpty()) output.result.value else null

    /**
     * Chars to delete before the caret (Output.deletion_range, which Undo after a commit sends): Mozc gives an offset
     * back from the caret and a length, both in code points, counted in [before], the field's text before the caret.
     * Only a range that ends at the caret is taken; 0 for none.
     */
    @JvmStatic
    fun deleteBefore(output: Output, before: CharSequence): Int {
        if (!output.hasDeletionRange()) return 0
        val range = output.deletionRange
        if (range.length <= 0 || range.offset != -range.length) return 0
        return CodePoints.utf16Before(before, range.length)
    }

    /** The composition mode Mozc says it is in, null when it does not say. */
    @JvmStatic
    fun mode(output: Output): CompositionMode? = when {
        output.hasMode() -> output.mode
        output.hasStatus() && output.status.hasMode() -> output.status.mode
        else -> null
    }

    /** The annotation drawn small beside a candidate: Mozc's prefix, suffix and description, those it gives. */
    @JvmStatic
    fun annotation(annotation: Annotation?): String? {
        if (annotation == null) return null
        val parts = listOf(annotation.prefix, annotation.suffix, annotation.description).filter { it.isNotEmpty() }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }
}
