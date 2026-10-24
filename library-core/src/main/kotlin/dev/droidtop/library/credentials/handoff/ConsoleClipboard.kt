package dev.droidtop.library.credentials.handoff

/** What reading the console's clipboard found. */
sealed interface ClipRead {
    /** The clip's text, as the console holds it (not yet checked against the size limit). */
    data class Text(val text: String) : ClipRead

    /** Nothing on the clipboard. */
    data object Empty : ClipRead

    /** The source marked the clip sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`): it is never sent to the phone. */
    data object Sensitive : ClipRead

    /** The clip holds something other than text (an image, a file, a URI). */
    data object NotText : ClipRead

    /** Android would not let droidtop read it (it only lets the app in front, or the active input method, read the clipboard). */
    data object Unavailable : ClipRead
}

/**
 * The console's clipboard as the session's shared clipboard uses it (docs/SPEC.md, "Type from another device", the
 * shared clipboard, Droidtop/tracker#645). The Android implementation runs both calls on the main thread; the
 * session calls them from the server's thread, outside its own lock, so a fake stands in for it in tests.
 */
interface ConsoleClipboard {
    /** Puts [text] on the clipboard; false when Android refused. */
    fun write(text: String): Boolean

    fun read(): ClipRead

    /** No clipboard at all: what a session has until Android gives it one. */
    object None : ConsoleClipboard {
        override fun write(text: String) = false

        override fun read(): ClipRead = ClipRead.Unavailable
    }
}

/** The rules for what may leave the console's clipboard, with no Android in them so they are unit-tested. */
object ClipRules {
    /** The shared clipboard carries at most this many bytes of UTF-8 text, each way. */
    const val MAX_BYTES = 64 * 1024

    /** How many characters of a clip the console shows when the phone sent it. */
    const val PREVIEW_CHARS = 200

    /** The largest request body a clipboard send can have: every byte of the text percent-encoded, plus the form's own fields. */
    const val MAX_BODY = MAX_BYTES * 3 + 1024

    fun fits(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    /**
     * What a clip is, decided before any of its content is touched: sensitive first (its text is never read),
     * then whether it is text at all. [text] is only asked for once both pass.
     */
    fun classify(present: Boolean, sensitive: Boolean, isText: Boolean, text: () -> CharSequence?): ClipRead = when {
        !present -> ClipRead.Empty
        sensitive -> ClipRead.Sensitive
        !isText -> ClipRead.NotText
        else -> text()?.toString()?.let { if (it.isEmpty()) ClipRead.Empty else ClipRead.Text(it) } ?: ClipRead.NotText
    }

    /** One line of [text] for the console: whitespace runs collapsed, cut at [PREVIEW_CHARS] with an ellipsis. */
    fun preview(text: String): String {
        val line = text.trim().replace(Regex("\\s+"), " ")
        return if (line.length > PREVIEW_CHARS) line.take(PREVIEW_CHARS) + "\u2026" else line
    }
}
