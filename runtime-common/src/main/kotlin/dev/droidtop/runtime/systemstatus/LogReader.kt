package dev.droidtop.runtime.systemstatus

import dev.droidtop.runtime.tasks.TaskManager

/**
 * Performance > Logs (docs/SPEC.md "The companion's tabs", Logs; Droidtop/tracker#414 slice C18): Android's log through
 * the helper app (`logcat -d -v threadtime`, then only what is newer while the page shows), or without it droidtop's
 * own (`logcat --pid` of droidtop, which needs no permission). Filtered by level, tag and app, with one tap for the
 * running game's process only. Reads are shell work: off the main thread.
 */
object LogReader {
    /** Android's levels, least to most severe. */
    enum class Level(val letter: Char, val label: String) { VERBOSE('V', "Verbose"), DEBUG('D', "Debug"), INFO('I', "Info"), WARN('W', "Warning"), ERROR('E', "Error"), FATAL('F', "Fatal") }

    data class Entry(val time: String, val pid: Int, val tid: Int, val level: Level, val tag: String, val message: String) {
        /** The line as logcat printed it, for Save and Share. */
        fun line(): String = "$time $pid $tid ${level.letter} $tag: $message"
    }

    /** What to show: at least [minLevel]; [tag] when not blank (contains, any case); only [pids] when not null. */
    data class Filter(val minLevel: Level = Level.VERBOSE, val tag: String = "", val pids: Set<Int>? = null)

    // threadtime: "10-09 23:01:02.345  1234  5678 E Tag     : message"
    private val THREADTIME = Regex("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+(.*?)\\s*: (.*)$")

    /** One threadtime line, or null for a header or a wrapped line. Pure. */
    fun parse(line: String): Entry? {
        val m = THREADTIME.find(line) ?: return null
        val level = Level.entries.first { it.letter == m.groupValues[4][0] }
        return Entry(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), level, m.groupValues[5], m.groupValues[6])
    }

    fun matches(entry: Entry, filter: Filter): Boolean =
        entry.level.ordinal >= filter.minLevel.ordinal &&
            (filter.tag.isBlank() || entry.tag.contains(filter.tag.trim(), ignoreCase = true)) &&
            (filter.pids == null || entry.pid in filter.pids)

    /** The pids `pidof` printed for a package ("1234 5678"), for the running-game filter. Pure. */
    fun parsePids(text: String): Set<Int> = text.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.toSet()

    /** The most lines kept on the page; older ones scroll away. */
    const val MAX_LINES = 2_000

    /**
     * Log lines since [sinceTime] ("MM-DD HH:MM:SS.mmm", null for everything still in the buffer): Android's with the
     * helper app, else droidtop's own. [ownPid] is droidtop's process. Blocks.
     */
    fun read(ownPid: Int, sinceTime: String?): Pair<Boolean, List<Entry>> {
        val shell = TaskManager.shell
        val provider = runCatching { shell.capabilities().shellCommand }.getOrDefault(false)
        // The last lines of the buffer each time; what is not newer than [sinceTime] was shown already.
        val args = listOf("logcat", "-d", "-v", "threadtime", "-t", (if (sinceTime == null) MAX_LINES else REFRESH_LINES).toString())
        val text = if (provider) {
            shell.exec(args)?.takeIf { it.exit == 0 }?.stdout
        } else {
            runCatching {
                val process = ProcessBuilder(args + listOf("--pid", ownPid.toString())).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
            }.getOrNull()
        }
        return provider to newerThan(text.orEmpty().lineSequence().mapNotNull(::parse).toList(), sinceTime)
    }

    /** The entries after [sinceTime] (logcat's "MM-DD HH:MM:SS.mmm" sorts as text within a year). Pure. */
    fun newerThan(entries: List<Entry>, sinceTime: String?): List<Entry> =
        if (sinceTime == null) entries else entries.filter { it.time > sinceTime }

    /** How many lines each refresh asks for while the page shows. */
    const val REFRESH_LINES = 400

    /** The running game's processes, through the helper app (`pidof`). Blocks. */
    fun pidsOf(packageName: String): Set<Int> =
        TaskManager.shell.exec(listOf("pidof", packageName))?.takeIf { it.exit == 0 }?.let { parsePids(it.stdout) }.orEmpty()
}
