package dev.droidtop.pluginhost

/**
 * A plugin's words, made safe to show before droidtop shows any of them (Droidtop/tracker#784, slice 4; the owner:
 * "Parse and sanitize it against any injection attacks and stuff first"). A plugin is third-party code, and what it says
 * in an error or a job's summary reaches droidtop's screens, its job lines, its notices and TalkBack. So every such
 * text is cleaned once, where it enters droidtop ([PluginReply.parse], [PluginResult]), and no screen gets it raw:
 * - markup is removed (a tag, or an HTML entity such as `&lt;`), so nothing reads as a page's own;
 * - control characters become spaces and invisible format characters are dropped: the bidirectional overrides and
 *   isolates that can show text in a different order than it is (U+202A to U+202E, U+2066 to U+2069), zero-width
 *   characters and the byte-order mark;
 * - lone surrogates are dropped;
 * - whitespace runs become one space, and the text is bounded.
 *
 * droidtop never interprets the result: it is shown as text, never parsed as HTML, a format string, a link or an
 * intent. Whether a cleaned message may stand as the person's sentence is [SourceFailures.plain]'s call.
 */
object PluginText {
    /** The longest a message (one sentence for the person) may be. */
    const val MESSAGE_MAX = 200

    /** The longest a title may be. */
    const val TITLE_MAX = 80

    /** The longest a detail (behind "Technical details") may be. */
    const val DETAIL_MAX = 400

    private val TAG = Regex("<[^>]{0,500}>")
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{1,10});")
    private val SPACES = Regex("\\s+")

    /** [raw] cleaned and bounded to [max] characters; null when nothing is left. Pure. */
    fun clean(raw: String?, max: Int = MESSAGE_MAX): String? {
        if (raw.isNullOrEmpty()) return null
        // Bounded before any work, so a huge reply costs nothing to clean.
        val text = raw.take(max * 4).replace(TAG, " ").replace(ENTITY, " ")
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    val cp = Character.toCodePoint(c, text[i + 1])
                    if (keeps(cp)) out.append(c).append(text[i + 1])
                    i += 2
                    continue
                }
                Character.isSurrogate(c) -> Unit
                Character.isISOControl(c) -> out.append(' ')
                keeps(c.code) -> out.append(c)
            }
            i++
        }
        val spaced = out.toString().replace(SPACES, " ").trim()
        if (spaced.isEmpty()) return null
        return if (spaced.length > max) spaced.take(max - 1).trimEnd() + "…" else spaced
    }

    /** Whether code point [cp] is kept: not an invisible format character (Cf: bidi controls, zero-width, BOM) or unassigned private use. */
    private fun keeps(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED -> false
        else -> true
    }
}
