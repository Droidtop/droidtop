package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentsManager
import com.winlator.core.KeyValueSet
import com.winlator.core.ProcessHelper
import com.winlator.xserver.ScreenInfo
import dev.droidtop.library.PcLaunchResult
import dev.droidtop.runtime.SafeDelete
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

    const val NO_ENVIRONMENT = "There is no Windows environment yet. Run Set up Windows games first."

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

    // ---- Windows components (the winetricks verbs this runtime has) ----

    /** A Windows component the prefix can have: [native] is "Windows' own files", false is Wine's own. */
    data class Component(val id: String, val name: String, val native: Boolean)

    /**
     * Every component [entryId]'s prefix lists, with whether it carries Windows' own files. These are
     * the components of droidtop's component catalog (the `wincomponents` files of
     * Droidtop/droidtop-components): this runtime's counterpart of a winetricks verb.
     */
    suspend fun components(context: Context, entryId: String?): List<Component>? = withContext(Dispatchers.IO) {
        val container = PcContainers.forGame(context, entryId) ?: return@withContext null
        KeyValueSet(container.winComponents).map { (id, on) -> Component(id, PrefixSettings.componentName(id), on == "1") }
    }

    /**
     * Installs component [id] into [entryId]'s prefix ([native]), or switches it back to Wine's own,
     * now: the choice is written the way the prefix settings write it and the prefix is prepared
     * straight away, which fetches the component's files from the catalog (checked against their
     * SHA-256) and puts them in, instead of leaving it for the next start. Refused while any Wine
     * process runs, because that process owns the prefix.
     */
    suspend fun setComponent(context: Context, entryId: String?, id: String, native: Boolean, onStatus: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            runningMessage()?.let { return@withContext it }
            val container = PcContainers.forGame(context, entryId) ?: return@withContext NO_ENVIRONMENT
            if (KeyValueSet(container.winComponents).none { it[0] == id }) return@withContext "This prefix has no component $id"
            val name = PrefixSettings.componentName(id)
            onStatus(if (native) "Installing $name…" else "Switching $name back to Wine's own…")
            PrefixSettings.set(context, entryId, PrefixSettings.COMPONENT + id, if (native) "1" else "0").join()
            val fresh = PcContainers.forGame(context, entryId) ?: return@withContext NO_ENVIRONMENT
            val missing = BionicWineEngine(context).readiness(fresh) as? WineEngineReadiness.Missing
            if (missing != null) return@withContext "Saved, and applied at the next start: ${missing.reason}"
            runCatching {
                WindowsBackbone.awaitReady(context)
                WinePrefixPreparation.prepare(context, fresh, ScreenInfo(fresh.screenSize))
            }.fold(
                { if (native) "Installed $name" else "$name is Wine's own again" },
                { "Saved, but couldn't apply it now (${it.message ?: it}); it is tried again at the next start" },
            )
        }

    // ---- the prefix folder, read only ----

    /** The prefix's own files (its `.wine` folder, whose `drive_c` is C:), or null with no environment. Disk work. */
    suspend fun folderOf(context: Context, entryId: String?): File? = withContext(Dispatchers.IO) {
        PcContainers.forGame(context, entryId)?.let { File(it.rootDir, ".wine") }?.takeIf { it.isDirectory }
    }

    // ---- reset ----

    /**
     * Makes [entryId]'s prefix new: its `.wine` folder is removed (every Windows program installed in
     * it, its registry and the saves of every game that keeps them inside it) and unpacked again from
     * the Wine build's own prefix, and the prefix's settings are kept. The next start sets it up the
     * way the first one did. Refused while any Wine process runs.
     */
    suspend fun reset(context: Context, entryId: String?, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        runningMessage()?.let { return@withContext it }
        val container = PcContainers.forGame(context, entryId) ?: return@withContext NO_ENVIRONMENT
        val root = container.rootDir
        val prefix = File(root, ".wine")
        onStatus("Removing the old prefix…")
        if (!SafeDelete.deleteWithin(root, prefix)) return@withContext "Couldn't remove all of the prefix; some of it may be gone. Reset it again to finish"
        onStatus("Unpacking a fresh prefix…")
        val contents = ContentsManager(context).apply { syncContents() }
        if (!ContainerManager(context).extractContainerPatternFile(container.wineVersion, contents, root, null)) {
            return@withContext "Couldn't unpack a fresh prefix for ${container.wineVersion}. Reset it again once that Wine build is installed"
        }
        // What the last start recorded about the prefix is no longer true: the next start sets it up whole.
        RESET_EXTRAS.forEach { container.putExtra(it, null) }
        container.saveData()
        "Reset. Its next start sets the prefix up again"
    }

    /** A line to refuse with while any Wine process runs, else null. */
    private fun runningMessage(): String? =
        if (ProcessHelper.listRunningWineProcesses().isEmpty()) null
        else "A Windows program is still running. Stop every Wine process first (Prefix tools), then try again"

    /** The extras a start records about a prefix ([WinePrefixPreparation]); cleared, the next start runs the whole pass. */
    private val RESET_EXTRAS = listOf(
        "appVersion", "imgVersion", "appliedContainerVariant", "appliedWineVersion", "dxwrapper", "graphicsDriver",
        "desktopTheme", "xaudioDllsExtracted", "wincomponents", "audioDriver", "startupSelection",
    )

    /** The prefix's C: drive, where a tool starts. */
    internal fun driveC(container: Container): File = File(container.rootDir, ".wine/drive_c")

    private val PROGRAM_EXTENSIONS = setOf("exe", "msi", "bat", "cmd")
}
