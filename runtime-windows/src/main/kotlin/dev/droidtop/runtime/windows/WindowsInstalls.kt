package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.ContainerManager
import dev.droidtop.library.settings.LibraryPaths
import dev.droidtop.library.settings.PathChange
import dev.droidtop.runtime.SafeDelete
import dev.droidtop.runtime.windows.utils.ContainerUtils
import dev.droidtop.runtime.windows.utils.CustomGameScanner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Install a Windows game" (docs/SPEC.md 7c, "Install a new game"): a GOG offline installer or any
 * setup program runs in a NEW prefix of its own, and what it installed becomes a library game that
 * starts in that prefix.
 *
 * The installer is read where it is, never copied or moved. Its folder (and the folder the person chose
 * to install into, if any) is mapped into the new prefix as a drive ([PrefixDrives]), so the installer
 * finds its own data files beside it and can be told to install into the person's folder; droidtop
 * writes nothing there itself. By default the game goes inside the prefix, which touches none of the
 * person's folders.
 *
 * The new prefix is made with the id its game will have ([CustomGameScanner.reserveGameId]: a folder
 * game `CUSTOM_GAME_n` starts in the prefix of that id, [PcContainers.forGame]), so adding the installed
 * folder as game n ([addGame]) is all it takes for the library row to start in it. An install that has
 * been started and not yet added is [Pending]; [PcContainers.ofContainer] opens its prefix for the
 * prefix tools and settings. [addGame] is the one door a game folder becomes a library game through
 * for a prefix made here; a general "Add a game" flow (Droidtop/tracker#407) calls it.
 */
object WindowsInstalls {

    /** An installer that has been run in a new prefix whose game has not been added yet. */
    data class Pending(
        /** The new prefix's id, and the id its game will have. */
        val id: String,
        val title: String,
        val installer: String,
        /** The folder the person chose to install into, or null: inside the prefix. */
        val target: String?,
        val startedAtMs: Long,
        /** Folders that were there before the installer ran, so they are not offered as what it installed. */
        val before: Set<String>,
    )

    /** Every started install, newest first. Disk work. */
    suspend fun pending(context: Context): List<Pending> = withContext(Dispatchers.IO) {
        prefs(context).all.values.mapNotNull { (it as? String)?.let(::parse) }.sortedByDescending { it.startedAtMs }
    }

    /**
     * Makes a new prefix from the shared one's settings and runs [installer] in it. Returns the line the
     * settings row shows. [target] is the person's folder to install into, null for inside the prefix.
     */
    suspend fun begin(context: Context, installer: File, target: File?, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        if (!installer.isFile) return@withContext "${installer.name} is not a file on this device"
        if (!WinePrefixTools.isProgram(installer)) return@withContext "${installer.name} is not an installer Wine can run (.exe, .msi, .bat or .cmd)"
        if (target != null && !target.isDirectory) return@withContext "${target.path} is not a folder on this device"
        val shared = PcContainers.forGame(context, null) ?: return@withContext WinePrefixTools.NO_ENVIRONMENT
        (BionicWineEngine(context).readiness(shared) as? WineEngineReadiness.Missing)?.let { return@withContext it.reason }

        val settings = ContainerUtils.toContainerData(shared)
        var drives = settings.drives
        // The installer's own folder, so it finds the data files beside it (a GOG installer's .bin parts).
        installer.parentFile?.let { folder -> PrefixDrives.withFolder(drives, folder)?.let { drives = it.drives } }
        var targetLetter: Char? = null
        if (target != null) {
            val mapped = PrefixDrives.withFolder(drives, target)
                ?: return@withContext "That folder cannot be reached from a Windows prefix (no free drive letter, or a colon in its path)"
            drives = mapped.drives
            targetLetter = mapped.letter
        }
        val title = installer.nameWithoutExtension.ifBlank { "Windows game" }
        val manager = ContainerManager(context)
        val gameId = CustomGameScanner.reserveGameId { !manager.hasContainer(containerIdOf(it)) }
        val id = containerIdOf(gameId)
        onStatus("Making a new Windows prefix…")
        val container = try {
            PcContainers.createNamed(context, id, title, settings.copy(name = title, execArgs = "", drives = drives))
        } catch (e: Exception) {
            return@withContext "Couldn't make a new prefix: ${e.message ?: e}"
        }
        val before = findCandidates(WinePrefixTools.driveC(container), null, emptySet()).map { it.absolutePath }.toSet()
        save(context, Pending(id, title, installer.absolutePath, target?.absolutePath, System.currentTimeMillis(), before))
        onStatus("Starting the installer…")
        val result = WinePrefixTools.runIn(context, container, installer)
        if (!result.succeeded) return@withContext "The new prefix is made, but ${installer.name} did not start: ${result.detail}"
        "Started ${installer.name} in a new prefix" +
            (targetLetter?.let { ". To install into ${target?.name}, choose drive $it: in the installer" } ?: "") +
            ". When it finishes, come back here and add the game"
    }

    /** Runs the installer of [id] again in its prefix. */
    suspend fun runAgain(context: Context, id: String): String = withContext(Dispatchers.IO) {
        val pending = find(context, id) ?: return@withContext "That install is no longer listed"
        val container = PcContainers.forGame(context, PcContainers.ofContainer(id)) ?: return@withContext "Its prefix is gone"
        val installer = File(pending.installer)
        if (!installer.isFile) return@withContext "${installer.name} is not where it was"
        val result = WinePrefixTools.runIn(context, container, installer)
        if (result.succeeded) "Started ${installer.name}" else "Couldn't start ${installer.name}: ${result.detail}"
    }

    /** The folders the installer of [id] has put in its prefix (or in the folder chosen) since it started. Disk work. */
    suspend fun candidates(context: Context, id: String): List<File> = withContext(Dispatchers.IO) {
        val pending = find(context, id) ?: return@withContext emptyList()
        val container = PcContainers.forGame(context, PcContainers.ofContainer(id)) ?: return@withContext emptyList()
        findCandidates(WinePrefixTools.driveC(container), pending.target?.let(::File), pending.before)
    }

    /**
     * Makes [folder], a folder the installer of [id] put in its prefix (or in the folder chosen), a library
     * game that starts in that prefix, and ends the install's listing. The folder stays where it is.
     * Returns the line the settings row shows.
     */
    suspend fun addGame(context: Context, id: String, folder: File): String = withContext(Dispatchers.IO) {
        val pending = find(context, id) ?: return@withContext "That install is no longer listed"
        val container = PcContainers.forGame(context, PcContainers.ofContainer(id)) ?: return@withContext "Its prefix is gone"
        val target = pending.target?.let(::File)
        val inside = PrefixFolderView.isInside(WinePrefixTools.driveC(container), folder) ||
            (target != null && PrefixFolderView.isInside(target, folder))
        if (!folder.isDirectory || !inside) return@withContext "${folder.name} is not a folder this installer put in its prefix"
        val gameId = id.removePrefix(ID_PREFIX).toIntOrNull() ?: return@withContext "That install has no game number"
        WindowsBackbone.awaitReady(context)
        DroidtopGameIdStore.install(context)
        CustomGameScanner.adopt(folder, gameId)
            ?: return@withContext "droidtop could not take ${folder.name} as a game (another game holds its number)"
        container.name = folder.name
        container.saveData()
        forget(context, id)
        // The library looks at exactly this folder (docs/SPEC.md 7g, "Targeted indexing").
        LibraryPaths.report(context, PathChange(added = listOf(folder.absolutePath)))
        "Added ${folder.name} to your library. It starts in the prefix this installer made"
    }

    /** Deletes the prefix of [id] and ends its listing: the install is thrown away. Refused while Wine runs. */
    suspend fun discard(context: Context, id: String): String = withContext(Dispatchers.IO) {
        WinePrefixTools.runningMessage()?.let { return@withContext it }
        val container = PcContainers.forGame(context, PcContainers.ofContainer(id))
        val root = container?.rootDir
        val home = root?.parentFile
        if (root != null && home != null && !SafeDelete.deleteWithin(home, root)) return@withContext "Couldn't delete all of the prefix. Try again"
        forget(context, id)
        "Threw the install away. Nothing outside its prefix was touched"
    }

    // ---- what an installer left ----

    private val INSTALL_ROOTS = setOf("program files", "program files (x86)", "gog games", "games")
    private val SYSTEM_TOP = setOf("windows", "users", "programdata", "temp", "\$recycle.bin")
    private val SYSTEM_FOLDERS = setOf(
        "common files", "internet explorer", "windows media player", "windows nt", "windows mail",
        "windows photo viewer", "windows defender", "windowsapps", "uninstall information", "msbuild",
        "reference assemblies", "wine", "microsoft.net",
    )

    /**
     * The folders an installer may have put its game in, newest first: the folders inside the places
     * installers use (`Program Files`, `GOG Games`, `Games`), any other folder at the top of C:, and the
     * folders inside the one the person chose to install into, without [before]; the chosen folder itself
     * comes last. Windows' own folders are never offered. Disk work.
     */
    internal fun findCandidates(driveC: File, target: File?, before: Set<String>): List<File> {
        val found = LinkedHashSet<File>()
        for (dir in driveC.listFiles { f -> f.isDirectory }.orEmpty()) {
            val name = dir.name.lowercase()
            when {
                name in INSTALL_ROOTS ->
                    dir.listFiles { f -> f.isDirectory && f.name.lowercase() !in SYSTEM_FOLDERS }.orEmpty().forEach { found += it }
                name in SYSTEM_TOP -> Unit
                else -> found += dir
            }
        }
        if (target != null) target.listFiles { f -> f.isDirectory }.orEmpty().forEach { found += it }
        val fresh = found.filter { it.absolutePath !in before }.sortedByDescending { it.lastModified() }
        return if (target != null && target.isDirectory) fresh + target else fresh
    }

    internal fun containerIdOf(gameId: Int): String = "$ID_PREFIX$gameId"

    private const val ID_PREFIX = "CUSTOM_GAME_"

    // ---- the list of started installs ----

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("droidtop_windows_installs", Context.MODE_PRIVATE)

    private fun find(context: Context, id: String): Pending? = prefs(context).getString(id, null)?.let(::parse)

    private fun save(context: Context, pending: Pending) {
        val json = JSONObject()
            .put("id", pending.id).put("title", pending.title).put("installer", pending.installer)
            .put("target", pending.target ?: JSONObject.NULL).put("startedAtMs", pending.startedAtMs)
            .put("before", JSONArray(pending.before.toList()))
        prefs(context).edit().putString(pending.id, json.toString()).commit()
    }

    private fun forget(context: Context, id: String) {
        prefs(context).edit().remove(id).commit()
    }

    private fun parse(text: String): Pending? = runCatching {
        val json = JSONObject(text)
        Pending(
            id = json.getString("id"),
            title = json.getString("title"),
            installer = json.getString("installer"),
            target = if (json.isNull("target")) null else json.getString("target"),
            startedAtMs = json.getLong("startedAtMs"),
            before = json.getJSONArray("before").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() },
        )
    }.getOrNull()
}
