package dev.droidtop.library

import java.io.File

/** Outcome of a real PC-runtime launch attempt. */
data class PcLaunchResult(val succeeded: Boolean, val detail: String)

/**
 * The seam through which `library-core` reaches droidtop's real PC
 * runtimes: Wine/Box64 for Windows executables (`runtime-windows`'
 * `WineSession`) and the container runtime for native Linux builds
 * (`runtime-common`'s `NativeLinuxGameSession`).
 *
 * It exists because `library-core` cannot depend on either module, and
 * both need a live container session that only `:app` knows how to
 * obtain. Rather than duplicate that knowledge, `:app` installs an
 * implementation into [PcGameRuntimeRegistry] at startup and every
 * caller here goes through this interface -- the same swappable-seam
 * pattern [LaunchDisplay.chooser] and gamenative-tux's own
 * `LinuxContainerBackend` already use.
 *
 * Real gap this closed: [GameLaunchStrategy.WINE_PREFIX] and
 * [GameLaunchStrategy.LINUX_CONTAINER] were both dead `error()` stubs
 * ("isn't wired to a running session yet"), so a detected engine game
 * with a perfectly good Windows build could be *offered* Wine and then
 * fail on activation. Now the strategy either really launches or reports
 * a specific, actionable reason it can't.
 */
interface PcGameRuntime {
    /**
     * Whether a launch could succeed right now. False when no container
     * session is live -- droidtop's Wine runs as a process *inside* a
     * running container (see `WineSession`'s own doc comment), so there
     * is nothing to launch into until Desktop mode has connected one.
     */
    val isAvailable: Boolean

    /** Runs a Windows executable under Wine/Box64, with [gameRoot] as its working directory. */
    suspend fun launchWindows(executable: File, gameRoot: File): PcLaunchResult

    /** Runs a native Linux executable directly inside the container. */
    suspend fun launchLinux(executable: File, gameRoot: File): PcLaunchResult
}

/** Set once by `:app` at startup; read by every `library-core` launch path. */
object PcGameRuntimeRegistry {
    @Volatile
    var runtime: PcGameRuntime? = null
}

/**
 * Picks the file a PC launch strategy should actually run out of a game
 * folder. droidtop's engine detection proves an engine is *present*; it
 * never had to name the launchable file, because nothing consumed one
 * until the runtimes were wired up.
 *
 * Deliberately conservative -- it returns null rather than guessing when
 * a folder has several equally plausible candidates, so the caller can
 * say "several executables here, pick one" instead of silently starting
 * the wrong thing (a patcher, an uninstaller, a crash reporter).
 */
object GameExecutableResolver {

    // Real installer/uninstaller/tooling names that sit beside a game's
    // own executable and must never be mistaken for it.
    private val NON_GAME_PREFIXES = listOf("unins", "setup", "install", "vcredist", "dxsetup", "crashpad", "crashreport")

    fun windowsExecutable(gameRoot: File): File? = pickOne(
        candidates(gameRoot) { it.extension.equals("exe", ignoreCase = true) },
        gameRoot,
    )

    /**
     * A native Linux launcher. Ren'Py and most engines that ship a Linux
     * build put a `<GameName>.sh` beside the Windows `.exe`; extensionless
     * ELF binaries are the other real shape.
     */
    fun linuxExecutable(gameRoot: File): File? {
        val shell = candidates(gameRoot) { it.extension.equals("sh", ignoreCase = true) }
        if (shell.isNotEmpty()) return pickOne(shell, gameRoot)
        return pickOne(candidates(gameRoot) { it.extension.isEmpty() && it.canExecute() }, gameRoot)
    }

    private fun candidates(gameRoot: File, matches: (File) -> Boolean): List<File> =
        (gameRoot.listFiles() ?: emptyArray())
            .filter { it.isFile && matches(it) }
            .filterNot { file -> NON_GAME_PREFIXES.any { file.name.lowercase().startsWith(it) } }

    /**
     * One candidate wins outright. With several, prefer one named after
     * the game folder itself -- the near-universal convention for
     * engine exports (`Eternum-0.9.5-pc/Eternum.exe`) and the one real
     * disambiguation that isn't a guess. Otherwise give up.
     */
    private fun pickOne(candidates: List<File>, gameRoot: File): File? {
        if (candidates.size == 1) return candidates.single()
        if (candidates.isEmpty()) return null
        val folderName = gameRoot.name.substringBefore('-').trim().lowercase()
        return candidates.firstOrNull { it.nameWithoutExtension.lowercase() == folderName }
            ?: candidates.firstOrNull { it.nameWithoutExtension.lowercase().startsWith(folderName) && folderName.length >= 3 }
    }
}
