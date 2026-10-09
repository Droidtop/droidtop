package org.pocketworkstation.pckeyboard

/**
 * The keyboard's clipboard history as a plain model (docs/SPEC.md 6a, "Editing helpers", Droidtop/tracker#340):
 * bounded, newest first, with pinned entries kept and listed first. Text only; a clip longer than [maxChars] is
 * dropped rather than truncated (the clipboard bridge does the same, SPEC 6d), a blank clip or one the source
 * flagged sensitive is never recorded, and nothing is recorded while incognito. No Android classes, so it is
 * tested on the JVM; [ClipboardHistoryStore] owns the file and the listener.
 */
class ClipboardHistory(private val maxUnpinned: Int = 50, private val maxChars: Int = 5000) {
    data class Entry(val text: String, val pinned: Boolean, val at: Long)

    private val items = ArrayList<Entry>()

    /** Pinned entries first, then the rest, each newest first. */
    val entries: List<Entry>
        get() = items.filter { it.pinned }.sortedByDescending { it.at } +
            items.filter { !it.pinned }.sortedByDescending { it.at }

    val size: Int get() = items.size

    /** Records [text]; false when it was refused. A repeat moves to the top and keeps its pin. */
    fun add(text: String, now: Long, sensitive: Boolean = false, incognito: Boolean = false): Boolean {
        if (sensitive || incognito || text.isBlank() || text.length > maxChars) return false
        val old = items.indexOfFirst { it.text == text }
        val pinned = old >= 0 && items[old].pinned
        if (old >= 0) items.removeAt(old)
        items.add(Entry(text, pinned, now))
        trim()
        return true
    }

    fun setPinned(text: String, pinned: Boolean): Boolean {
        val index = items.indexOfFirst { it.text == text }
        if (index < 0 || items[index].pinned == pinned) return false
        items[index] = items[index].copy(pinned = pinned)
        if (!pinned) trim()
        return true
    }

    fun delete(text: String): Boolean = items.removeAll { it.text == text }

    /** Clears the history; pinned entries stay unless [keepPinned] is false. */
    fun clear(keepPinned: Boolean = true) {
        if (keepPinned) items.removeAll { !it.pinned } else items.clear()
    }

    /** The oldest unpinned entries beyond the bound go. */
    private fun trim() {
        var extra = items.count { !it.pinned } - maxUnpinned
        if (extra <= 0) return
        val oldestFirst = items.filter { !it.pinned }.sortedBy { it.at }
        for (entry in oldestFirst) {
            if (extra-- <= 0) break
            items.remove(entry)
        }
    }

    /** One entry per line: `P` or `-`, a tab, the time, a tab, the text with backslash, newline, tab and return escaped. */
    fun encode(): String = buildString {
        for (entry in items) {
            append(if (entry.pinned) 'P' else '-').append('\t').append(entry.at).append('\t').append(escape(entry.text)).append('\n')
        }
    }

    /** Replaces the entries with those in [encoded]; a line that does not parse is skipped. */
    fun restore(encoded: String) {
        items.clear()
        for (line in encoded.split('\n')) {
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) continue
            val at = parts[1].toLongOrNull() ?: continue
            val text = unescape(parts[2])
            if (text.isBlank() || text.length > maxChars || items.any { it.text == text }) continue
            items.add(Entry(text, parts[0] == "P", at))
        }
        trim()
    }

    private companion object {
        fun escape(text: String): String = buildString {
            for (c in text) {
                when (c) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    '\r' -> append("\\r")
                    else -> append(c)
                }
            }
        }

        fun unescape(text: String): String = buildString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    when (text[i + 1]) {
                        'n' -> append('\n')
                        't' -> append('\t')
                        'r' -> append('\r')
                        else -> append(text[i + 1])
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}
