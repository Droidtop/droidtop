package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How ONE game starts under Wine, where that differs from what droidtop
 * works out on its own: which program in the game's folder, with which
 * arguments, from which folder (docs/SPEC.md 7i, "Overrides").
 *
 * Per game, never on the prefix: droidtop's prefix is shared by every
 * Windows game without one of its own (§5b), and one game's program and
 * arguments are not another's. Paths are relative to the game's folder,
 * so a folder that moves with its game keeps them.
 *
 * Today the one writer is the Lutris importer (§7e3); [source] says so on
 * the game's own screen, and clearing it returns the game to detection.
 */
@Serializable
data class WineGameSettings(
    /** The program to run, relative to the game's folder, `/`-separated. */
    val executable: String? = null,
    val arguments: List<String> = emptyList(),
    /** The folder to start in, relative to the game's folder; null is the game's folder. */
    val workingDir: String? = null,
    /** Where these came from, in the person's words ("Lutris: GOG installer"). */
    val source: String? = null,
)

/** [WineGameSettings] by [LibraryEntry.id], the same keying as [LaunchStrategyOverridePrefs]. */
object WineGameSettingsPrefs {
    private const val KEY_PREFIX = "droidtop_wine_game_settings_"
    private val json = Json { ignoreUnknownKeys = true }

    fun get(context: Context, entryId: String): WineGameSettings? =
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + entryId, null)
            ?.let { runCatching { json.decodeFromString(WineGameSettings.serializer(), it) }.getOrNull() }

    fun set(context: Context, entryId: String, settings: WineGameSettings?) {
        val prefs = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
        if (settings == null) {
            prefs.remove(KEY_PREFIX + entryId)
        } else {
            prefs.putString(KEY_PREFIX + entryId, json.encodeToString(WineGameSettings.serializer(), settings))
        }
        prefs.apply()
    }
}

/** What a Windows launch of one game runs: the program, where it starts, and its arguments. */
data class WindowsLaunch(val executable: File, val workingDir: File, val arguments: List<String>)

/**
 * The one answer to "what does this game run under Wine", for every
 * Windows launch path (the engine detector's and the PC provider's).
 *
 * The game's own [WineGameSettings] win when they still name something
 * inside [gameRoot] -- re-checked here on every launch, canonically, so a
 * setting can never lead out of the game's folder even if the folder
 * changed after it was saved. Otherwise it is what
 * [GameExecutableResolver.windowsExecutable] detects, as before.
 * Disk work: never on the main thread.
 */
object WindowsLaunchResolver {
    fun resolve(settings: WineGameSettings?, gameRoot: File): WindowsLaunch? {
        val root = runCatching { gameRoot.canonicalFile }.getOrNull() ?: return null
        val chosen = settings?.executable?.let { inside(root, it) }?.takeIf { it.isFile && it.extension.equals("exe", ignoreCase = true) }
        // Arguments and a start folder belong to the program they were
        // saved with; a detected program starts plain, as it always did.
        if (settings == null || chosen == null) {
            val detected = GameExecutableResolver.windowsExecutable(gameRoot) ?: return null
            return WindowsLaunch(detected, gameRoot, emptyList())
        }
        val workingDir = settings.workingDir?.let { inside(root, it) }?.takeIf { it.isDirectory } ?: gameRoot
        return WindowsLaunch(chosen, workingDir, settings.arguments)
    }

    fun resolve(context: Context, entryId: String, gameRoot: File): WindowsLaunch? =
        resolve(WineGameSettingsPrefs.get(context, entryId), gameRoot)

    private fun inside(root: File, relative: String): File? {
        val file = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }
}
