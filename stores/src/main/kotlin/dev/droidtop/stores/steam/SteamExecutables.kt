package dev.droidtop.stores.steam

/**
 * Which program of an installed Steam game is the game (GameNative's
 * SteamService.getInstalledExe, choosePrimaryExe and its scoring, GPL-3.0).
 * Pure: the files come in as [Candidate]s read from the depot manifests the
 * download keeps, so the rules are testable without an install.
 *
 * In order: the developer's own launch entry, unless it is a stub (a tiny
 * launcher, a crash handler, a set-up program); else the best-scoring program
 * the manifests flag; else the biggest program of the biggest depot; else the
 * first Windows launch entry as Steam names it.
 */
internal object SteamExecutables {
    /** One file a depot manifest lists: its path in the game folder, its executable flag, its size, its depot's size. */
    data class Candidate(val path: String, val executableFlag: Boolean, val size: Long, val depotSize: Long)

    private val UE_SHIPPING = Regex(""".*-win(32|64)(-shipping)?\.exe$""", RegexOption.IGNORE_CASE)
    private val UE_BINARIES = Regex(""".*/binaries/win(32|64)/.*\.exe$""", RegexOption.IGNORE_CASE)
    private val NEGATIVE = listOf("crash", "handler", "viewer", "compiler", "tool", "setup", "unins", "eac", "launcher", "steam")
    private val GENERIC_NAME = Regex("^[a-z]\\d{1,3}\\.exe$", RegexOption.IGNORE_CASE)
    private val STUB_WORDS = listOf("launcher", "steam", "crash", "handler", "setup", "unins", "eac")

    /** A small or helper program that is not the game itself. */
    fun isStub(candidate: Candidate): Boolean {
        val name = candidate.path.substringAfterLast('/').lowercase()
        return GENERIC_NAME.matches(name) || STUB_WORDS.any { it in name } || candidate.size < 1_000_000
    }

    private fun fuzzyMatch(a: String, b: String): Boolean =
        a.replace(Regex("[^a-z]"), "").take(5) == b.replace(Regex("[^a-z]"), "").take(5)

    fun score(candidate: Candidate, gameName: String): Int {
        var s = 0
        val path = candidate.path.lowercase()
        if (UE_SHIPPING.matches(path)) s += 300
        if (UE_BINARIES.containsMatchIn(path)) s += 250
        if (!path.contains('/')) s += 200
        if (path.contains(gameName) || fuzzyMatch(path, gameName)) s += 100
        if (NEGATIVE.any { it in path }) s -= 150
        if (GENERIC_NAME.matches(candidate.path.substringAfterLast('/'))) s -= 200
        if (candidate.executableFlag) s += 50
        return s
    }

    /**
     * The game's program among [files] (every file the installed depots'
     * manifests list, paths with '/'), or the first of [windowsLaunchEntries]
     * when the manifests are not there; null when there is nothing at all.
     */
    fun choose(files: List<Candidate>, windowsLaunchEntries: List<String>, folderName: String): String? {
        val targets = windowsLaunchEntries.map { it.lowercase() }.toSet()
        files.firstOrNull { it.path.lowercase() in targets && !isStub(it) }?.let { return it.path }
        val programs = files.filter { it.executableFlag || it.path.endsWith(".exe", ignoreCase = true) }
        val pool = programs.filterNot(::isStub).ifEmpty { programs }
        val name = folderName.lowercase()
        pool.maxWithOrNull { a, b ->
            val sa = score(a, name)
            val sb = score(b, name)
            if (sa != sb) sa - sb else a.size.compareTo(b.size)
        }?.let { return it.path }
        return windowsLaunchEntries.firstOrNull()
    }

    /** The launch entries Steam names for Windows: their program ends in .exe (the OS tags on entries are not reliable). */
    fun windowsLaunchEntries(app: SteamApp): List<LaunchInfo> =
        app.config.launch.filter { it.executable.endsWith(".exe", ignoreCase = true) }
}
