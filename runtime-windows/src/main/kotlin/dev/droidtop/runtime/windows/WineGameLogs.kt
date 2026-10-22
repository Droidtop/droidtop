package dev.droidtop.runtime.windows

import dev.droidtop.library.diagnostics.RunRecords
import java.io.File

/**
 * Finds the logs a Windows game wrote itself, so a run's record carries the real reason it failed: Wine's own
 * output rarely does (console, 2026-10-22: a Unity game stayed black while Wine printed nothing, and the cause was
 * in Unity's Player.log in the prefix, Droidtop/tracker#601). Reads only; the caller attaches what is found
 * ([RunRecords.attach]) off the main thread. Only the logs of known engines and a few common file names next to
 * the executable: not a search of the game's folder.
 */
object WineGameLogs {
    /** A log to attach: what to call it, and where it is. */
    data class Found(val label: String, val file: File)

    /** Names a game commonly writes its log under, next to its executable (matched without regard to case). */
    val COMMON_NAMES = listOf(
        "output_log.txt", "log.txt", "debug.log", "error.log", "errors.txt", "errorlog.txt", "crash.log",
        "game.log", "console.log", "stdout.txt", "stderr.txt", "output.txt", "debug.txt", "error.txt",
    )

    private const val UNITY_DATA_SUFFIX = "_Data"

    /**
     * The directories the game's files are in: the one holding the executable [target] (a Unix path; a Windows
     * path inside the prefix is reached through [workingDir]) and the game's working directory.
     */
    fun gameDirs(target: String?, workingDir: File?): List<File> {
        val fromTarget = target?.takeIf { it.startsWith("/") }?.let { File(it).parentFile }
        return listOfNotNull(fromTarget, workingDir).filter { it.isDirectory }.distinct()
    }

    /**
     * The logs for a run of [target] in [gameDirs], with [prefixRoot] the prefix's own directory (its `drive_c`
     * is where a Windows program keeps its user data). At most [RunRecords.MAX_ATTACHMENTS], in this order:
     * Unity's Player.log and Player-prev.log, the older Unity `output_log.txt`, then files next to the executable.
     */
    fun find(gameDirs: List<File>, prefixRoot: File?, exeName: String?): List<Found> {
        val found = LinkedHashMap<String, Found>()
        fun add(label: String, file: File) {
            if (file.isFile) found.putIfAbsent(runCatching { file.canonicalPath }.getOrDefault(file.path), Found(label, file))
        }
        val lowRoots = prefixRoot?.let(::localLowDirs).orEmpty()
        for (dir in gameDirs) {
            for (data in dataDirs(dir)) {
                val product = data.name.removeSuffix(UNITY_DATA_SUFFIX)
                for (log in unityPlayerLogs(data, product, lowRoots)) add("Unity ${log.name}", log)
                childIgnoringCase(data, "output_log.txt")?.let { add("Unity output_log.txt", it) }
            }
        }
        val stem = exeName?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        for (dir in gameDirs) {
            val names = dir.list().orEmpty()
            val wanted = COMMON_NAMES + listOfNotNull(stem?.let { "$it.log" }, stem?.let { "${it}_log.txt" })
            for (want in wanted) {
                names.firstOrNull { it.equals(want, ignoreCase = true) }?.let { add("Game log ($it)", File(dir, it)) }
            }
        }
        return found.values.take(RunRecords.MAX_ATTACHMENTS)
    }

    /** `<prefix>/drive_c/users/<user>/AppData/LocalLow` for each user the prefix has. */
    private fun localLowDirs(prefixRoot: File): List<File> =
        File(prefixRoot, "drive_c/users").listFiles { f -> f.isDirectory }.orEmpty()
            .map { File(it, "AppData/LocalLow") }.filter { it.isDirectory }

    /** The game's Unity data folders: `<Product>_Data` beside the executable. */
    private fun dataDirs(gameDir: File): List<File> =
        gameDir.listFiles { f -> f.isDirectory && f.name.endsWith(UNITY_DATA_SUFFIX) }.orEmpty().sortedBy { it.name }

    /**
     * Unity keeps Player.log under `LocalLow/<Company>/<Product>`; the company and product are the two lines of
     * `app.info` in the data folder. Without it, any company folder holding the product's folder answers.
     */
    private fun unityPlayerLogs(data: File, product: String, lowRoots: List<File>): List<File> {
        val info = runCatching { File(data, "app.info").readLines().map(String::trim).filter(String::isNotEmpty) }.getOrDefault(emptyList())
        val names = listOf("Player.log", "Player-prev.log")
        val result = ArrayList<File>()
        for (low in lowRoots) {
            val productDirs = if (info.size >= 2) {
                listOf(File(low, info[0] + "/" + info[1]))
            } else {
                low.listFiles { f -> f.isDirectory }.orEmpty().map { File(it, product) }
            }
            for (productDir in productDirs.filter { it.isDirectory }) {
                for (name in names) File(productDir, name).takeIf { it.isFile }?.let(result::add)
            }
        }
        return result
    }

    private fun childIgnoringCase(dir: File, name: String): File? =
        dir.list()?.firstOrNull { it.equals(name, ignoreCase = true) }?.let { File(dir, it) }
}
