package dev.droidtop.runtime.tasks

/** One standard task read out of `dumpsys activity activities`. */
data class DumpedTask(val taskId: Int, val packageName: String, val displayId: Int, val visible: Boolean)

/**
 * Reads the task list out of `dumpsys activity activities`, filtered by the shell to the `Display #N` and
 * `Task{...}` lines. The format is the one AOSP's `Task.toString()` writes
 * (`Task{hash #id type=standard A=uid:package U=0 visible=true ...}`) under a `Display #N (activities from
 * top to bottom):` header, with the older `A=package` form accepted too. Tolerant by design: a line that
 * does not look like a task is skipped, never an error. The order is the dump's, most recent first per display.
 */
object ActivityDump {
    /** What the shell runs: the dump is far over the 64 KiB a provider returns, so it is cut down to the lines read here. */
    val COMMAND: List<String> = listOf(
        "sh",
        "-c",
        "dumpsys activity activities | grep -E 'Display #[0-9]+|Task\\{'",
    )

    private val display = Regex("""Display #(\d+)""")
    private val task = Regex("""Task\{[0-9a-fA-F]+ #(\d+)\b([^}]*)\}""")
    private val affinity = Regex("""\bA=(?:\d+:)?([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)""")
    private val type = Regex("""\btype=(\w+)""")
    private val visible = Regex("""\bvisible=(true|false)""")

    fun parse(text: String): List<DumpedTask> {
        var displayId = 0
        val seen = HashSet<Int>()
        val out = ArrayList<DumpedTask>()
        for (line in text.lineSequence()) {
            display.find(line)?.let { displayId = it.groupValues[1].toInt() }
            val match = task.find(line) ?: continue
            val id = match.groupValues[1].toInt()
            val rest = match.groupValues[2]
            // Home, recents and other special task types are not apps the user opened.
            val kind = type.find(rest)?.groupValues?.get(1)
            if (kind != null && kind != "standard") continue
            val pkg = affinity.find(rest)?.groupValues?.get(1) ?: continue
            if (!seen.add(id)) continue
            out += DumpedTask(id, pkg, displayId, visible.find(rest)?.groupValues?.get(1) == "true")
        }
        return out
    }
}
