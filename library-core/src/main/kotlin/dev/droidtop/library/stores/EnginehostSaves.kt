package dev.droidtop.library.stores

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Cloud saves for a store game that Enginehost runs (docs/SPEC.md 7g, "Stores", "A game Enginehost runs";
 * Droidtop/tracker#580). droidtop owns the save folder: each such game gets a folder of its own in shared storage,
 * shaped like a Wine prefix ([prefixFor]: `drive_c/users/<user>/...`), so a store's sync writes into it exactly as it
 * writes into a real prefix and needs no second mapping. The launch hands Enginehost [saveRoot] as the inline
 * `saveRoot` key: the folder in it that directly holds the game's save files, as the store's cloud names it
 * ([StoreLibrary.saveFolder]), unchanged. The owner, 2026-10-18: "What we provide is a link to the saves, as they are
 * provided to us. This SHOULD be an exact mapping of whatever the save root for a game is." Enginehost and its cores
 * use that folder as the game's save folder as it is, so droidtop needs no engine knowledge.
 *
 * A game is recorded here when Enginehost launches it ([prepare]), by its store key and its folder, so the sync
 * after Enginehost reports the game ended ([keyForFolder]) and every later sync of that key ([prefixFor]) find
 * the same folder. Preferences reads and writes, and folders are made: off the main thread.
 */
object EnginehostSaves {
    private const val PREFS = "droidtop_enginehost_saves"
    private const val FOLDER_PREFIX = "folder:"
    private const val PASSED_PREFIX = "passed:"

    /** The Windows user inside the folder; Steam's cloud paths never name it. */
    const val USER = "droidtop"

    /** The store key ("steam:440") whose cloud keeps this game's saves, from its id or the store id it carries; null for none. */
    fun storeKeyOf(entryId: String, storeId: String?): String? =
        listOfNotNull(entryId, storeId).firstOrNull { key -> StoreLibraries.forKey(key)?.hasCloudSaves == true }

    /** The folder for [storeKey], in shared storage where Enginehost may write: `droidtop/saves/<store>-<id>`. */
    fun folderFor(storeKey: String): File =
        File(File(File(Environment.getExternalStorageDirectory(), "droidtop"), "saves"), storeKey.replace(':', '-'))

    /**
     * Makes [storeKey]'s folder and records that Enginehost runs its game from [gameFolder], so the sync before the
     * launch finds the folder ([prefixFor]). False when the folder cannot be made.
     */
    fun prepare(context: Context, storeKey: String, gameFolder: File): Boolean {
        val drive = File(folderFor(storeKey), "drive_c")
        if (!drive.isDirectory && !drive.mkdirs()) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(storeKey, true)
            .putString(FOLDER_PREFIX + gameFolder.absolutePath, storeKey)
            .apply()
        return true
    }

    /**
     * The folder to name as [storeKey]'s game's `saveRoot` for this launch: the folder of its saves in its prefix,
     * as the store names it, made when missing. Asked after the sync before the launch, which reads what the store
     * says. Null when the store names none or it cannot be made; the game then saves to the person's own location
     * and nothing is uploaded when it ends. The folder is recorded, so [usedOurFolder] compares with it.
     */
    suspend fun saveRoot(context: Context, storeKey: String): File? {
        val prefix = prefixFor(context, storeKey)
        val folder = prefix?.let { StoreLibraries.forKey(storeKey)?.saveFolder(context, storeKey.substringAfter(':'), it) }
            ?.takeIf { it.isDirectory || it.mkdirs() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PASSED_PREFIX + storeKey, folder?.absolutePath)
            .apply()
        return folder
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
     * Whether the folder the engine used ([saveFolder], from Enginehost's report) is the one droidtop named for
     * [storeKey]'s last launch. Enginehost reports exactly that folder when it took it; anything else (a refused
     * path, an engine that saves beside the game) leaves droidtop's folder with nothing new to upload.
     */
    fun usedOurFolder(context: Context, storeKey: String, saveFolder: String?): Boolean {
        val passed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PASSED_PREFIX + storeKey, null)
        return sameFolder(passed, saveFolder)
    }

    /** Whether [passed] and [reported] name the same folder, path for path. Pure but for the paths it builds. */
    internal fun sameFolder(passed: String?, reported: String?): Boolean =
        !passed.isNullOrBlank() && !reported.isNullOrBlank() && File(passed).absolutePath == File(reported).absolutePath
}
