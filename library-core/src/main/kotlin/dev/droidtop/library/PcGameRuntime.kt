package dev.droidtop.library

import java.io.File

/** Outcome of a real PC-runtime launch attempt. */
data class PcLaunchResult(val succeeded: Boolean, val detail: String)

/**
 * Outcome of setting up the Windows environment. Separate from
 * [PcLaunchResult] despite the same shape because it answers a different
 * question -- "is there something to launch into" rather than "did this
 * game start" -- and a caller that conflates the two reports the wrong
 * thing to the user.
 */
data class PcProvisionResult(val succeeded: Boolean, val detail: String)

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

    /**
     * Whether a Windows environment has actually been set up yet.
     *
     * Distinct from [isAvailable], which asks whether a container
     * session is live right now. Both have to be true to launch, and
     * they fail for different reasons the user can act on differently:
     * nothing installed yet versus installed but not running.
     */
    val isProvisioned: Boolean

    /**
     * Creates the Wine container and installs the Windows environment
     * into it, reporting progress through [onStatus].
     *
     * [gamesRoots] are mapped as Wine drives at creation rather than
     * afterwards. That is the whole point of doing this here: upstream's
     * default drive list hardcodes its own package's private storage, so
     * a container made from it cannot see a games folder on an SD card
     * at all -- which is exactly the "arbitrary storage locations"
     * problem, and matches what a Winlator investigation found (no SD
     * drive mapping existed; adding one made the path resolve).
     *
     * Idempotent: an existing container is reported as already set up
     * rather than duplicated. These environments are hundreds of
     * megabytes and silently making a second one would be a real cost.
     */
    suspend fun provision(gamesRoots: List<File>, onStatus: (String) -> Unit): PcProvisionResult

    /** Runs a Windows executable under Wine/Box64, with [gameRoot] as its working directory. */
    suspend fun launchWindows(executable: File, gameRoot: File): PcLaunchResult

    /** Runs a native Linux executable directly inside the container. */
    suspend fun launchLinux(executable: File, gameRoot: File): PcLaunchResult
}

/**
 * Which Wine drive letter each games root gets.
 *
 * One rule, used by both the runtime that creates the container and the
 * settings screen that previews the mapping -- the screen promises to
 * show what a game will actually see, and a second, slightly different
 * implementation there is exactly how that promise would quietly break.
 *
 * D onwards: A/B are historically floppies and C: is the Windows drive
 * inside the container itself. A path containing `:` is skipped rather
 * than encoded, because the container's drive string finds each letter
 * by the character before a `:` -- one such path would corrupt every
 * mapping after it. exFAT already rejects `:` in names, so this is
 * near-unreachable, but a silently malformed drive list is far harder to
 * diagnose than a missing entry. Stops at Z.
 */
object WineDriveMapping {
    fun assign(gamesRoots: List<String>): List<Pair<Char, String>> {
        val usable = gamesRoots.filterNot { it.contains(':') }.distinct()
        return usable.take('Z' - 'D' + 1).mapIndexed { index, path -> ('D' + index) to path }
    }
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
     * A native Linux launcher, in the order the real shapes should win:
     *
     * 1. `<GameName>.sh` -- Ren'Py and most engines that ship a Linux
     *    build put a shell wrapper beside the Windows `.exe`, and when
     *    one exists it is the entry point the build intends (it sets up
     *    the environment before exec'ing the ELF next to it).
     * 2. `<GameName>.x86_64` / `<GameName>.x86` -- the conventional
     *    extension for a bare Linux ELF launcher, and the shape this
     *    resolver used to miss entirely. Nothing engine-specific: it is
     *    what Godot's Linux export template produces (see
     *    [GameEngineDetector]'s own GDPC probe, which already knew these
     *    two extensions) and what most other exporters emit for a
     *    64-bit Linux build. Deliberately NOT gated on [File.canExecute]:
     *    the games live on removable storage, and exFAT/FAT32 carry no
     *    execute bit at all, so requiring one would reject every real
     *    SD-card install.
     * 3. An extensionless executable -- the remaining real shape, and the
     *    only one where the execute bit is the only evidence the file is
     *    a program rather than a data blob, so it stays required there.
     */
    fun linuxExecutable(gameRoot: File): File? {
        val shell = candidates(gameRoot) { it.extension.equals("sh", ignoreCase = true) }
        if (shell.isNotEmpty()) return pickOne(shell, gameRoot)
        val elf = candidates(gameRoot) { it.extension.lowercase() in LINUX_ELF_EXTENSIONS }
        if (elf.isNotEmpty()) return pickOne(elf, gameRoot)
        return pickOne(candidates(gameRoot) { it.extension.isEmpty() && it.canExecute() }, gameRoot)
    }

    /** Conventional extensions for a native Linux ELF launcher. */
    private val LINUX_ELF_EXTENSIONS = setOf("x86_64", "x86")

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
