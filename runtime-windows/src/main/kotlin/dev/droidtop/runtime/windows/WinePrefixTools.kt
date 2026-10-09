package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.ProcessHelper
import dev.droidtop.library.PcLaunchResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lutris-style tools for one Wine prefix (docs/SPEC.md 7c, "Prefix tools"):
 * the programs Wine ships for looking after a prefix, running any program in
 * it, and stopping every Wine process. Each one starts through the one way a
 * Windows program starts in a prefix ([launchInPrefix]), as the app's own uid
 * and never with root (docs/SPEC.md 5b), on the same screen a game uses
 * ([WineGameActivity]), worded as a program's rather than a game's.
 *
 * [entryId] picks the prefix the way a launch does ([PcContainers.forGame]):
 * null is the shared environment, a library game's id is the prefix that game
 * starts in. Every function here is main-safe.
 */
object WinePrefixTools {

    /** A program Wine ships, started with Wine's own name for it. */
    enum class Program(val title: String, val target: String, val arguments: List<String> = emptyList()) {
        /** Windows version, DLL overrides, drives and audio: Wine's own settings live here. */
        CONFIGURATION("Wine configuration", "winecfg"),

        /** The prefix's registry, which a game's fix is often a key in. */
        REGISTRY_EDITOR("Registry editor", "regedit"),

        /** `cmd` has no terminal to attach to here, so `wineconsole` gives it a window of its own. */
        COMMAND_PROMPT("Command prompt", "wineconsole", listOf("cmd")),
    }

    internal const val NO_ENVIRONMENT = "There is no Windows environment yet. Run Set up Windows games first."

    /** What a program is started as: the file Wine is handed, and what follows it. */
    internal data class ProgramLaunch(val target: String, val arguments: List<String>)

    /**
     * How [file] is started. An `.msi` is handed to Wine's own `start /unix`,
     * which opens it with the prefix's registered installer (msiexec) as a
     * double-click in Explorer would; anything else runs directly, as a
     * library game does.
     */
    internal fun launchOf(file: File): ProgramLaunch =
        if (file.name.endsWith(".msi", ignoreCase = true)) {
            ProgramLaunch("start", listOf("/unix", file.absolutePath))
        } else {
            ProgramLaunch(file.absolutePath, emptyList())
        }

    /** Whether [file] is a kind of file Wine starts as a program: the ones "Run a program" offers. */
    fun isProgram(file: File): Boolean = file.extension.lowercase() in PROGRAM_EXTENSIONS

    /** Starts [program] in [entryId]'s prefix. Returns the line the settings row shows. */
    suspend fun open(context: Context, entryId: String?, program: Program): String {
        val container = withContext(Dispatchers.IO) { PcContainers.forGame(context, entryId) } ?: return NO_ENVIRONMENT
        val result = launchInPrefix(
            BionicWineEngine(context),
            container,
            program.target,
            driveC(container),
            program.arguments,
            tool = program.title,
        )
        return if (result.succeeded) "Opened ${program.title}" else "Couldn't open ${program.title}: ${result.detail}"
    }

    /**
     * Runs [file] in [entryId]'s prefix, from where it is: nothing is copied or moved, so the
     * program sees its own folder as it is. Returns the line the settings row shows.
     */
    suspend fun run(context: Context, entryId: String?, file: File): String {
        val container = withContext(Dispatchers.IO) { PcContainers.forGame(context, entryId) } ?: return NO_ENVIRONMENT
        if (!withContext(Dispatchers.IO) { file.isFile }) return "${file.name} is not a file on this device"
        if (!isProgram(file)) return "${file.name} is not a program Wine can run (.exe, .msi, .bat or .cmd)"
        val result = runIn(context, container, file)
        return if (result.succeeded) "Started ${file.name}" else "Couldn't start ${file.name}: ${result.detail}"
    }

    /** [file] in [container]: the one place a program the person picked starts ([launchOf]). */
    internal suspend fun runIn(context: Context, container: Container, file: File): PcLaunchResult {
        val launch = launchOf(file)
        return launchInPrefix(
            BionicWineEngine(context),
            container,
            launch.target,
            file.parentFile ?: file,
            launch.arguments,
            tool = file.name,
        )
    }

    /**
     * Stops every Wine process, droidtop's own guests and any a game left behind (the same list a
     * launch clears before it starts). Returns the line the settings row shows.
     */
    suspend fun killAll(): String = withContext(Dispatchers.IO) {
        val running = ProcessHelper.listRunningWineProcesses().size
        if (running == 0) {
            "No Wine process is running"
        } else {
            ProcessHelper.killAllWineProcesses()
            if (running == 1) "Stopped the 1 Wine process" else "Stopped the $running Wine processes"
        }
    }

    /** The prefix's C: drive, where a tool starts. */
    internal fun driveC(container: Container): File = File(container.rootDir, ".wine/drive_c")

    private val PROGRAM_EXTENSIONS = setOf("exe", "msi", "bat", "cmd")
}
