package dev.droidtop.library

import java.io.File

/** What a program in a game folder is for; only [GAME] and [TOOL] are things a person launches. */
enum class ExeRole {
    /** A program that could be the game. */
    GAME,

    /** A configuration screen, patcher, updater, editor or benchmark: launchable, never the first guess. */
    TOOL,

    /** `setup.exe`, `install.exe`: puts the game somewhere, is not the game. */
    INSTALLER,

    /** `unins000.exe`. */
    UNINSTALLER,

    /** A runtime the game needs installed (`vcredist_x64.exe`, `dxsetup.exe`, `dotnet`). */
    REDISTRIBUTABLE,

    /** A crash or bug reporter (`UnityCrashHandler64.exe`, `crashpad_handler.exe`). */
    CRASH_HANDLER,
    ;

    /** Collapsed: never offered as the game or as an alternative to it. */
    val collapsed: Boolean get() = this != GAME && this != TOOL
}

/** One file of a folder listing: its path below the folder, `/`-separated, and its size. */
data class ListedFile(val path: String, val size: Long = 0L) {
    val name: String get() = path.substringAfterLast('/')
    val depth: Int get() = path.count { it == '/' }
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** A program the classifier collapsed, and why. */
data class CollapsedExecutable(val path: String, val role: ExeRole)

/**
 * What a game folder's programs say (docs/SPEC.md 7n): the one [main] to
 * run, the [alternatives] a person could pick instead, what was
 * [collapsed] away, and the [engine] the existing detector reports.
 *
 * [main] is null when nothing is the game or when several equally likely
 * programs are (see [ambiguous]); never a guess.
 */
data class FolderFacts(
    val main: String?,
    val alternatives: List<String>,
    val collapsed: List<CollapsedExecutable>,
    val engine: GameEngine?,
) {
    /** Several programs could be the game and nothing says which: ask the person. */
    val ambiguous: Boolean get() = main == null && alternatives.size > 1
}

/**
 * ONE pure classifier over a folder's listing: which program is the game
 * (docs/SPEC.md 7n). [GameExecutableResolver] answers its questions through
 * it, so "which file does Play run" and "which programs does the page offer
 * instead" are one rule, not two.
 *
 * What it does, in order:
 *
 * 1. Every program is given a role from its name and the folders it sits in
 *    ([roleOf]). Installers, uninstallers, redistributables and crash
 *    handlers are collapsed: they are never the game and never offered.
 * 2. Of what is left, plain programs outrank tools; if there are no plain
 *    programs the tools are what is left.
 * 3. The shallowest layer of the pool decides (`Game.exe` beside the
 *    `bin/` payload is the game, not the payload). One program in the layer
 *    is the game; several are told apart by the folder's own title
 *    (`Some Game 1.2/SomeGame.exe`); otherwise nothing is chosen and the
 *    layer is the [FolderFacts.alternatives].
 *
 * The engine is not decided here: the caller hands in what
 * [GameEngineDetector] said, so engine detection stays in one place.
 * Nothing here touches the filesystem; [read] is the one bounded listing.
 */
object PcFolderClassifier {

    /** Folders whose programs are a runtime or a prerequisite, whatever they are called. */
    private val REDIST_FOLDERS = setOf(
        "redist", "redistributable", "redistributables", "_redist", "__redist", "commonredist", "directx", "dotnet",
        "vcredist", "prerequisites", "prereqs", "_commonredist", "__installer", "installer", "installers",
    )

    private val CRASH_FOLDERS = setOf("crashpad", "crashhandler", "crashreporter", "crashreports", "crashes")

    // Matched against the program's name with everything but letters and
    // digits removed, so `Unity Crash Handler` and `unity_crash_handler` agree.
    private val UNINSTALL_PREFIXES = listOf("unins")
    private val INSTALL_PREFIXES = listOf("setup", "install", "instmsi")
    private val REDIST_PREFIXES = listOf(
        "vcredist", "dxsetup", "dxwebsetup", "dotnet", "ndp", "netfx", "directx", "oalinst", "physx", "xnafx",
        "ue4prereq", "ueprereq", "windowsdesktopruntime",
    )
    private val CRASH_PREFIXES = listOf(
        "crashpad", "crashreport", "crashhandler", "unitycrashhandler", "bugreport", "errorreport", "crashsender",
        "crashmonitor", "notificationhelper",
    )
    private val TOOL_PREFIXES = listOf("config", "setting", "option", "patch", "updat", "bench", "editor", "modmanager")

    private val NOT_ALNUM = Regex("[^a-z0-9]")

    /** The extensions a program in a game folder can have. */
    private val LAUNCHABLE = setOf("exe", "sh", "x86_64", "x86")

    /** Whether [file] could be run at all; an extensionless program is the caller's to decide (it needs the execute bit). */
    fun isLaunchable(file: ListedFile): Boolean = file.extension in LAUNCHABLE

    /** The role of the program at [path] (below its game folder, `/`-separated). */
    fun roleOf(path: String): ExeRole {
        val parts = path.split('/')
        val folders = parts.dropLast(1).map { it.lowercase() }
        if (folders.any { it in CRASH_FOLDERS }) return ExeRole.CRASH_HANDLER
        if (folders.any { it in REDIST_FOLDERS }) return ExeRole.REDISTRIBUTABLE
        val base = NOT_ALNUM.replace(parts.last().substringBeforeLast('.').lowercase(), "")
        return when {
            UNINSTALL_PREFIXES.any { base.startsWith(it) } -> ExeRole.UNINSTALLER
            REDIST_PREFIXES.any { base.startsWith(it) } -> ExeRole.REDISTRIBUTABLE
            CRASH_PREFIXES.any { base.startsWith(it) } -> ExeRole.CRASH_HANDLER
            INSTALL_PREFIXES.any { base.startsWith(it) } -> ExeRole.INSTALLER
            TOOL_PREFIXES.any { base.startsWith(it) } -> ExeRole.TOOL
            else -> ExeRole.GAME
        }
    }

    /**
     * Classifies [programs] (the launchable files of a folder named
     * [folderName]). [engine] is what the engine detector said for the
     * folder, passed through.
     */
    fun classify(programs: List<ListedFile>, folderName: String, engine: GameEngine? = null): FolderFacts {
        val roles = programs.sortedWith(compareBy({ it.depth }, { it.path.lowercase() })).map { it to roleOf(it.path) }
        val collapsed = roles.filter { it.second.collapsed }.map { CollapsedExecutable(it.first.path, it.second) }
        val live = roles.filterNot { it.second.collapsed }
        val games = live.filter { it.second == ExeRole.GAME }.map { it.first }
        val pool = games.ifEmpty { live.map { it.first } }
        if (pool.isEmpty()) return FolderFacts(null, emptyList(), collapsed, engine)

        val shallowest = pool.minOf { it.depth }
        val layer = pool.filter { it.depth == shallowest }
        val main = when {
            layer.size == 1 -> layer.single()
            else -> namedAfter(layer, folderName)
        }
        val alternatives = live.map { it.first }.filter { it != main }.map { it.path }
        return FolderFacts(main?.path, alternatives, collapsed, engine)
    }

    /**
     * The one of [layer] named after the folder's title: its exact name, or
     * (for a title of three letters or more) the one name that begins with
     * it (`Game-Win64-Shipping` for `Game`). Two such names are no answer.
     */
    private fun namedAfter(layer: List<ListedFile>, folderName: String): ListedFile? {
        val key = GameNaming.nameKey(GameTitleParser.parseName(folderName).title)
        if (key.isEmpty()) return null
        fun base(file: ListedFile) = GameNaming.nameKey(file.name.substringBeforeLast('.'))
        layer.filter { base(it) == key }.singleOrNull()?.let { return it }
        if (key.length < 3) return null
        return layer.filter { base(it).startsWith(key) }.singleOrNull()
    }

    /** [classify] over [root]'s own listing, with the engine the detector reports for it. */
    fun classify(root: File, defs: List<EngineDef>): FolderFacts =
        classify(read(root).filter(::isLaunchable), root.name, GameEngineDetector.detectGame(root, defs)?.engine)

    /**
     * [root]'s files, below it to [maxDepth] folders, breadth first, never
     * more than [maxEntries] of them, hidden folders skipped. Bounded so a
     * game with a hundred thousand asset files costs the same as one with
     * ten; disk work, never on the main thread.
     */
    fun read(root: File, maxDepth: Int = 3, maxEntries: Int = 600): List<ListedFile> {
        val found = ArrayList<ListedFile>()
        val queue = ArrayDeque<Pair<File, String>>()
        queue.add(root to "")
        while (queue.isNotEmpty() && found.size < maxEntries) {
            val (folder, prefix) = queue.removeFirst()
            val children = folder.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
            for (child in children) {
                if (found.size >= maxEntries) break
                if (child.isDirectory) {
                    if (!child.name.startsWith(".") && prefix.count { it == '/' } + 1 < maxDepth) {
                        queue.add(child to prefix + child.name + "/")
                    }
                } else {
                    found += ListedFile(prefix + child.name, child.length())
                }
            }
        }
        return found
    }
}
