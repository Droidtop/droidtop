package dev.droidtop.library.stores

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Cloud saves for a store game that Enginehost runs (docs/SPEC.md 7g, "Stores", "A game Enginehost runs";
 * Droidtop/tracker#580). droidtop owns the save folder: each such game gets a folder of its own in shared storage,
 * shaped like a Wine prefix ([prefix]: `drive_c/users/<user>/...`), so a store's sync writes into it exactly as it
 * writes into a real prefix and needs no second mapping. The launch hands Enginehost [saveRoot] as the inline
 * `saveRoot` key: the folder an engine's system save location means for that launch, chosen in that prefix's
 * `AppData/Roaming` so the engine's own layout lands where the Windows build keeps its saves ([saveRootIn],
 * [toWindowsLayout]). Engines that save beside the game ignore it, and their saves stay where they are.
 *
 * A game is recorded here when Enginehost launches it ([remember]), by its store key and its folder, so the sync
 * after Enginehost reports the game ended ([keyForFolder]) and every later sync of that key ([prefixFor]) find
 * the same folder. Preferences reads and writes, and the folder is made on [saveRoot]: off the main thread.
 */
object EnginehostSaves {
    private const val PREFS = "droidtop_enginehost_saves"
    private const val FOLDER_PREFIX = "folder:"

    /** The Windows user inside the folder; Steam's cloud paths never name it. */
    const val USER = "droidtop"

    /** The store key ("steam:440") whose cloud keeps this game's saves, from its id or the store id it carries; null for none. */
    fun storeKeyOf(entryId: String, storeId: String?): String? =
        listOfNotNull(entryId, storeId).firstOrNull { key -> StoreLibraries.forKey(key)?.hasCloudSaves == true }

    /** The folder for [storeKey], in shared storage where Enginehost may write: `droidtop/saves/<store>-<id>`. */
    fun folderFor(storeKey: String): File =
        File(File(File(Environment.getExternalStorageDirectory(), "droidtop"), "saves"), storeKey.replace(':', '-'))

    /** The pseudo-prefix's `AppData/Roaming`, the folder Windows calls `%APPDATA%`. */
    fun roamingOf(prefixDir: File): File = File(prefixDir, "drive_c/users/$USER/AppData/Roaming")

    /**
     * The folder to name as [engine]'s save root inside [roaming], so the engine's own layout lands where the game's
     * Windows build keeps its saves and Steam Cloud's paths line up (Droidtop/tracker#580, read from Enginehost's
     * cores): Ren'Py saves straight under its root (`<root>/<save_directory>`) where Windows has
     * `%APPDATA%/RenPy/<save_directory>`, so it is given `RenPy` inside it; Godot already writes
     * `<root>/godot/app_userdata/<project>` (lowercase, see [toWindowsLayout]) or a custom user dir, so it is given
     * `%APPDATA%` itself, as is every other engine. Pure.
     */
    fun saveRootIn(roaming: File, engine: dev.droidtop.library.GameEngine): File =
        if (engine == dev.droidtop.library.GameEngine.RENPY) File(roaming, "RenPy") else roaming

    /** The save folder for this launch of an [engine] game, made when missing; null when it cannot be. */
    fun saveRoot(storeKey: String, engine: dev.droidtop.library.GameEngine): File? =
        saveRootIn(roamingOf(folderFor(storeKey)), engine).takeIf { it.isDirectory || it.mkdirs() }

    /**
     * Godot names its user folder `godot` on Android and Linux and `Godot` on Windows, where Steam Cloud's files come
     * from. Before a sync the folder takes the Windows name, after it the engine's ([toEngineLayout]), so the two
     * meet whichever way the files travel. A folder already named the target, or both present on a case-sensitive
     * card, is left as it is.
     */
    fun toWindowsLayout(roaming: File): Boolean = renameCase(roaming, "godot", "Godot")

    fun toEngineLayout(roaming: File): Boolean = renameCase(roaming, "Godot", "godot")

    /**
     * Renames [parent]'s entry named exactly [from] to [to], through a third name so it works on a card that folds
     * case too. False when there is no [from], or a [to] already stands beside it.
     */
    internal fun renameCase(parent: File, from: String, to: String): Boolean {
        val names = parent.list()?.toSet() ?: return false
        if (from !in names || to in names) return false
        val step = File(parent, "$to.droidtop-renaming")
        return File(parent, from).renameTo(step) && step.renameTo(File(parent, to))
    }

    /** Records that Enginehost runs [storeKey]'s game from [gameFolder]. */
    fun remember(context: Context, storeKey: String, gameFolder: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(storeKey, true)
            .putString(FOLDER_PREFIX + gameFolder.absolutePath, storeKey)
            .apply()
    }

    /** The store key of the game Enginehost ran from [gameFolder], or null when droidtop did not launch it there. */
    fun keyForFolder(context: Context, gameFolder: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FOLDER_PREFIX + File(gameFolder).absolutePath, null)

    /** The pseudo-prefix of [storeKey] when Enginehost runs that game, else null (a Wine game's prefix is the runtime's). */
    fun prefixFor(context: Context, storeKey: String): WinePrefixLocation? {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(storeKey, false)) return null
        val folder = folderFor(storeKey)
        return if (File(folder, "drive_c").isDirectory) WinePrefixLocation(folder, USER) else null
    }

    /**
     * Whether the folder the engine actually used ([saveFolder], from Enginehost's report) is under the [storeKey]
     * game's save folder: when Enginehost refused the path it used the person's own location, and droidtop's folder
     * then holds nothing new to upload. Pure but for the path it builds.
     */
    fun usedOurFolder(storeKey: String, saveFolder: String?): Boolean {
        if (saveFolder.isNullOrBlank()) return false
        val root = folderFor(storeKey).absolutePath
        val used = File(saveFolder).absolutePath
        return used == root || used.startsWith("$root/")
    }
}
