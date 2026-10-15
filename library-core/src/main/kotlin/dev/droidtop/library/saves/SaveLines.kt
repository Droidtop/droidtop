package dev.droidtop.library.saves

/**
 * The words a person reads about a game's saves (docs/SPEC.md 7o "Saves"): the value column, the line under it, and
 * the lines of the details page. Plain words only: no token, rule or manifest names outside the details page.
 */
object SaveLines {
    /** "just now", "5 min ago", "3 h ago", "2 days ago", "3 months ago". */
    fun ago(thenMs: Long, nowMs: Long): String {
        val seconds = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            seconds < 86_400L * 60 -> "${seconds / 86_400} days ago"
            else -> "${seconds / (86_400L * 30)} months ago"
        }
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun files(count: Int) = if (count == 1) "1 file" else "$count files"

    /** A folder the way a person names it: the internal storage's prefix is dropped. */
    fun folder(path: String): String = path.removePrefix("/storage/emulated/0/").let { if (it == path) path else "Internal storage/$it" }

    /** The short answer for the value column. */
    fun value(status: SaveStatus, nowMs: Long): String = when (status) {
        SaveStatus.TurnedOff -> "Not looked for"
        is SaveStatus.NoRule -> "No rule known"
        is SaveStatus.Unknown -> "Not known yet"
        is SaveStatus.NoneFound -> "None found"
        is SaveStatus.Found ->
            if (status.files == 0) "Memory card found" else "${files(status.files)}, ${ago(status.newestMs, nowMs)}"
    }

    /** The line under it: where, when, and why when the answer is not a find. */
    fun subtitle(report: SaveReport, nowMs: Long): String {
        val status = report.status
        val head = when (status) {
            SaveStatus.TurnedOff -> "You chose not to look for this game's saves."
            is SaveStatus.NoRule -> status.why.replaceFirstChar { it.uppercase() } + "."
            is SaveStatus.Unknown -> status.why.replaceFirstChar { it.uppercase() } + "."
            is SaveStatus.NoneFound -> "Looked in " + status.looked.joinToString(", ") + ", and found no saves."
            is SaveStatus.Found -> {
                val first = status.places.firstOrNull() ?: status.cards.first()
                val more = (status.places.size + status.cards.size - 1).let { if (it > 0) " and $it more place${if (it == 1) "" else "s"}" else "" }
                "In ${folder(first.folder)} (${first.label})$more: ${files(status.files)}, ${size(status.bytes)}, newest ${ago(status.newestMs, nowMs)}." +
                    (if (status.truncated) " Only the first ${SaveTemplates.MAX_FILES} files are counted." else "") +
                    (status.note?.let { " Not everywhere could be searched: $it." } ?: "")
            }
        }
        val tail = if (report.status is SaveStatus.Found || report.status is SaveStatus.NoneFound) report.matchedBy?.let { " This game is $it." }.orEmpty() else ""
        return head + tail
    }

    /** The details page's lines, one per fact. */
    fun details(report: SaveReport, nowMs: Long): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val status = report.status
        if (status is SaveStatus.Found) {
            status.places.forEach { out += "Saves: ${it.label}" to "${folder(it.folder)}: ${files(it.files)}, ${size(it.bytes)}, newest ${ago(it.newestMs, nowMs)}" }
            status.cards.forEach { out += "Memory card: ${it.label}" to "${folder(it.folder)}: ${files(it.files)}, ${size(it.bytes)}, newest ${ago(it.newestMs, nowMs)}. Shared by every game on this emulator." }
            status.states.forEach { out += "Save states: ${it.label}" to "${folder(it.folder)}: ${files(it.files)}. Not counted as saves: a state only loads in the same emulator version." }
        }
        if (status is SaveStatus.NoneFound) {
            status.states.forEach { out += "Save states: ${it.label}" to "${folder(it.folder)}: ${files(it.files)}. Not counted as saves: a state only loads in the same emulator version." }
        }
        report.rule?.let { out += "Rule" to it }
        report.matchedBy?.let { out += "Match" to it.replaceFirstChar { c -> c.uppercase() } }
        return out
    }
}
