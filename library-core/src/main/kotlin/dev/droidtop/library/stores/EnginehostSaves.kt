package dev.droidtop.library.stores

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Cloud saves for a store game that Enginehost runs (docs/SPEC.md 7g, "Stores", "A game Enginehost runs";
 * Droidtop/tracker#580). droidtop owns the save folder: each such game gets a folder of its own in shared storage,
 * shaped like a Wine prefix ([prefix]: `drive_c/users/<user>/...`), so a store's sync writes into it exactly as it
 * writes into a real prefix and needs no second mapping. The launch hands Enginehost [saveRoot], that prefix's
 * `AppData/Roaming`, as the inline `saveRoot` key: the folder an engine's system save location means for that
 * launch (Ren'Py and Godot name their own subfolder in it, as they do under `%APPDATA%` on Windows). Engines that
 * save beside the game ignore it, and their saves stay where they are.
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

    /** The save folder for this launch: the pseudo-prefix's `AppData/Roaming`, made when missing; null when it cannot be. */
    fun saveRoot(storeKey: String): File? =
        File(folderFor(storeKey), "drive_c/users/$USER/AppData/Roaming").takeIf { it.isDirectory || it.mkdirs() }

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
