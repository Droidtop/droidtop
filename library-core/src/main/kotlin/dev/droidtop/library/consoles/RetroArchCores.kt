package dev.droidtop.library.consoles

import android.content.Context
import android.content.Intent
import dev.droidtop.runtime.tasks.PrivilegedShell
import dev.droidtop.runtime.tasks.RiskyActions
import dev.droidtop.runtime.tasks.RiskyClass
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Checks launch requirements and directs core setup to RetroArch. */
object RetroArchCores {
    private const val SUFFIX = "_libretro_android.so"

    const val MANAGER_ID = "droidtop.retroarch"

    /**
     * What droidtop knows about one core. [PARTIAL]: the file is there but is not a whole ELF library (an interrupted
     * download), which RetroArch cannot load either. [UNKNOWN]: no root helper, so RetroArch's private folder cannot
     * be looked at.
     */
    enum class State { INSTALLED, MISSING, PARTIAL, UNKNOWN }

    /** A RetroArch launch's package and the core file it loads. */
    data class Need(val packageName: String, val core: String, val corePath: String)

    // --- pure parts -------------------------------------------------------------------------------

    fun isRetroArch(packageName: String): Boolean = packageName in DefaultPlayers.RETROARCH_PACKAGE_VARIANTS

    /** The cores folder RetroArch derives from its data dir (platform_unix.c, DEFAULT_DIR_CORE). */
    fun coresDir(packageName: String): String = "/data/user/0/$packageName/cores"

    /**
     * The core a RetroArch launch [template] loads, when its LIBRETRO extra names a buildbot-named
     * file in that package's own cores folder (`/data/user/0/<pkg>/cores` or `/data/data/<pkg>/cores`).
     * Anything else (a custom path, no LIBRETRO) is not droidtop's to install.
     */
    fun needFor(packageName: String, template: String): Need? {
        if (!isRetroArch(packageName)) return null
        val tokens = runCatching { AmStartCommandToIntentConverter.tokenize(template, null, null) }.getOrNull() ?: return null
        for (i in 1 until tokens.size - 1) {
            if (tokens[i] != "LIBRETRO" || tokens[i - 1] !in setOf("-e", "--es")) continue
            return needForPath(packageName, tokens[i + 1])
        }
        return null
    }

    /** The same, from the LIBRETRO path itself (a built launch intent's extra). */
    fun needForPath(packageName: String, path: String): Need? {
        if (!isRetroArch(packageName)) return null
        val file = File(path)
        val parent = file.parent ?: return null
        if (parent != coresDir(packageName) && parent != "/data/data/$packageName/cores") return null
        val name = file.name
        if (!name.endsWith(SUFFIX)) return null
        val core = name.removeSuffix(SUFFIX)
        if (!core.matches(CORE_ID)) return null
        return Need(packageName, core, "${coresDir(packageName)}/$name")
    }

    /**
     * The sentence the launch watchdog adds when a RetroArch launch is stuck: RetroArch shows no
     * error for a missing core, it sits black and stops answering (console, build 1386: a Game Boy
     * Color game with gambatte, which RetroArch never loaded, while mGBA ran through the same
     * launch). [suspect] decides whether it applies. Pure.
     */
    fun troubleHint(need: Need): String =
        "RetroArch shows a black screen when the core it is given is not installed or is incomplete. " +
            "This game needs ${checked(need)}: install it in RetroArch (Online Updater > Core Downloader)."

    /** The launch-failure sentence for a core droidtop saw is [State.MISSING] or [State.PARTIAL]. Pure. */
    fun missingMessage(need: Need, state: State): String =
        if (state == State.PARTIAL) {
            "The ${need.core} core in RetroArch is incomplete, so RetroArch would show a black screen (${checked(need)}). " +
                "Install it again in RetroArch (Online Updater > Core Downloader)."
        } else {
            "RetroArch does not have the core this game needs, so it would show a black screen (${checked(need)}). " +
                "Install it in RetroArch (Online Updater > Core Downloader)."
        }

    /** What droidtop looks for, named whole so a mismatch shows: the core, the RetroArch package and the file. Pure. */
    fun checked(need: Need): String = "${need.core} in ${need.packageName}, ${need.corePath}"

    /**
     * Whether [header] (at least the first 64 bytes) and [size] make a whole ELF file: the magic, and a section header
     * table that ends inside the file (ELF64 e_shoff at 0x28, e_shentsize 0x3A, e_shnum 0x3C; ELF32 at 0x20, 0x2E,
     * 0x30). An interrupted copy keeps the header and loses the table at the end. Pure.
     */
    fun elfWhole(header: ByteArray, size: Long): Boolean {
        if (header.size < 52 || header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) return false
        fun le(offset: Int, bytes: Int): Long = (0 until bytes).fold(0L) { acc, i -> acc or ((header[offset + i].toLong() and 0xFF) shl (8 * i)) }
        val (shoff, entsize, count) = when (header[4].toInt()) {
            2 -> if (header.size < 64) return false else Triple(le(0x28, 8), le(0x3A, 2), le(0x3C, 2))
            1 -> Triple(le(0x20, 4), le(0x2E, 2), le(0x30, 2))
            else -> return false
        }
        return shoff == 0L || size >= shoff + entsize * count
    }

    /**
     * The core a stuck RetroArch launch may be missing: [corePath]'s core unless the root helper
     * confirmed it is installed. Background only (it may ask the root helper).
     */
    fun suspect(packageName: String, corePath: String): Need? {
        val need = needForPath(packageName, corePath) ?: return null
        return need.takeIf { state(it) != State.INSTALLED }
    }

    private val CORE_ID = Regex("[a-z0-9_]+")

    /**
     * The helper when it runs as root (Sui) and Root-level commands is on in Settings > Risky actions; null otherwise,
     * and then nothing is asked of it, not even to look. Blocks on the provider: background only.
     */
    private fun rootShell(): PrivilegedShell? {
        if (!RiskyActions.allows(RiskyClass.ROOT_COMMANDS)) return null
        val shell = TaskManager.shell
        if (!shell.capabilities().shell) return null
        val id = shell.exec(listOf("id", "-u")) ?: return null
        return shell.takeIf { id.exit == 0 && id.stdout.trim() == "0" }
    }

    /** Whether RetroArch has [need]'s core, and whole ([elfWhole]). Background only. */
    fun state(need: Need): State {
        val root = rootShell() ?: return State.UNKNOWN
        val out = root.exec(listOf("test", "-e", need.corePath)) ?: return State.UNKNOWN
        if (out.exit != 0) return State.MISSING
        val size = root.exec(listOf("stat", "-c", "%s", need.corePath))?.takeIf { it.exit == 0 }?.stdout?.trim()?.toLongOrNull()
        val hex = root.exec(listOf("od", "-An", "-tx1", "-N64", need.corePath))?.takeIf { it.exit == 0 }?.stdout
        val header = hex?.split(' ', '\n')?.filter { it.isNotBlank() }?.mapNotNull { it.toIntOrNull(16)?.toByte() }?.toByteArray()
        // A read the helper could not answer says nothing against the file.
        if (size == null || header == null) return State.INSTALLED
        return if (elfWhole(header, size)) State.INSTALLED else State.PARTIAL
    }

    /** The core [system]'s chosen RetroArch launch needs, if RetroArch is the chosen emulator. Background only. */
    fun needForSystem(context: Context, system: ConsoleSystemDef): Need? {
        val player = resolvePlayer(context, system) ?: return null
        return needFor(player.packageName, player.argumentsTemplate)
    }

    suspend fun ensure(context: Context, need: Need, asked: Boolean = false): Outcome {
        if (withContext(Dispatchers.IO) { state(need) } == State.INSTALLED) return Outcome.Ready
        if (asked && !openRetroArch(context, need.packageName)) {
            return Outcome.Failed("Couldn’t open RetroArch. Open it to install or repair ${need.core} in Core Downloader.")
        }
        return Outcome.Manual("Install or repair ${need.core} in RetroArch: Online Updater > Core Downloader.")
    }

    /**
     * A launch refused before it started because droidtop saw RetroArch lacks [need]'s core, or has it incomplete
     * ([State.MISSING], [State.PARTIAL]): the shell offers to open RetroArch at the point of failure.
     */
    class Missing(val need: Need, message: String) : IllegalStateException(message)

    sealed interface Outcome {
        data object Ready : Outcome
        data class Manual(val line: String) : Outcome
        data class Failed(val line: String) : Outcome
    }

    suspend fun ensureForLibrary(context: Context, systems: List<ConsoleSystemDef>): String {
        val needs = withContext(Dispatchers.IO) {
            systems.mapNotNull { needForSystem(context, it) }
                .distinctBy { it.packageName to it.core }
                .filter { state(it) != State.INSTALLED }
        }
        if (needs.isEmpty()) return "No RetroArch cores need setup"
        val target = needs.first().packageName
        val cores = needs.filter { it.packageName == target }.map { it.core }.joinToString(", ")
        return if (openRetroArch(context, target)) "Use RetroArch's Core Downloader: $cores"
        else "Couldn’t open RetroArch. Open it and install these cores: $cores"
    }

    private fun openRetroArch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
