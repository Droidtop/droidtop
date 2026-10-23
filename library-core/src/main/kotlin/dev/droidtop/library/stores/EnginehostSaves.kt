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
    private const val KNOWN_PREFIX = "known:"
    private const val PENDING_PREFIX = "pending:"

    /** Steam Auto-Cloud searches at most this deep; saves adopted after a fallback are copied to the same depth. */
    private const val MAX_DEPTH = 5

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
     * as the store names it, made when missing. Asked after the sync before the launch. A folder the store named once
     * is remembered, so a launch while the store cannot be asked still has it. Null when there is none, or it is not
     * one Enginehost would take ([acceptablePath], [writable]): the game then saves to the person's own location and
     * [afterGameEnded] brings those saves over. The folder passed is recorded for [afterGameEnded].
     */
    suspend fun saveRoot(context: Context, storeKey: String): File? {
        val folder = (storeFolder(context, storeKey) ?: knownFolder(context, storeKey))
            ?.takeIf { acceptablePath(it.absolutePath) && writable(it) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Saves a past run left in the person's own location when no folder was known yet come in now.
        val pending = prefs.getString(PENDING_PREFIX + storeKey, null)
        val adopted = folder != null && pending != null && adopt(File(pending), folder) >= 0
        val edit = prefs.edit().putString(PASSED_PREFIX + storeKey, folder?.absolutePath)
        if (adopted) edit.remove(PENDING_PREFIX + storeKey)
        edit.apply()
        return folder
    }

    /** The folder the store names for [storeKey]'s saves in its prefix, remembered when it names one. */
    private suspend fun storeFolder(context: Context, storeKey: String): File? {
        val prefix = prefixFor(context, storeKey) ?: return null
        val folder = StoreLibraries.forKey(storeKey)?.saveFolder(context, storeKey.substringAfter(':'), prefix) ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KNOWN_PREFIX + storeKey, folder.absolutePath)
            .apply()
        return folder
    }

    private fun knownFolder(context: Context, storeKey: String): File? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KNOWN_PREFIX + storeKey, null)?.let(::File)

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
     * What to do when Enginehost reports [storeKey]'s game, run from [gameFolder], ended saving to [saveFolder]; true
     * when there is something to upload. The folder droidtop passed: upload. A folder in the game's own folder (an
     * engine that saves beside the game) or none: nothing. Any other folder is the person's own location, where
     * Enginehost saved because it did not take droidtop's folder or none was passed (owner, 2026-10-22: "enginehost
     * should write saves to the user folder, and tell droidtop when it doesn't work. That's not the user's problem"):
     * its saves are copied into the game's synced folder, newer files winning, and uploaded from there. When no
     * synced folder is known yet, that folder is kept and the next launch brings its saves in.
     */
    suspend fun afterGameEnded(context: Context, storeKey: String, gameFolder: String, saveFolder: String?): Boolean {
        val passed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PASSED_PREFIX + storeKey, null)
        if (sameFolder(passed, saveFolder)) return true
        // Only a folder Enginehost could have been handed (shared storage or a card), never one of droidtop's own: whoever
        // reports names it, and droidtop copies from it.
        val used = saveFolder?.let(::readableSaveFolder) ?: return false
        if (inside(used, runCatching { File(gameFolder).canonicalFile }.getOrDefault(File(gameFolder)))) return false
        val into = (storeFolder(context, storeKey) ?: knownFolder(context, storeKey))?.takeIf { it.isDirectory || it.mkdirs() }
        if (into == null) {
            // No folder known yet (the store cannot be asked now): the next launch brings these saves in ([saveRoot]).
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PENDING_PREFIX + storeKey, used.absolutePath)
                .apply()
            return false
        }
        if (sameFolder(into.absolutePath, used.absolutePath)) return true
        return adopt(used, into) >= 0
    }

    /**
     * [path] as a folder droidtop may copy saves from: an existing folder whose real path (links resolved) is one
     * [acceptablePath] takes. Null otherwise, so a report naming droidtop's private files, or a link to them, copies nothing.
     */
    fun readableSaveFolder(path: String): File? {
        if (path.isBlank()) return null
        val real = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return real.takeIf { it.isDirectory && acceptablePath(it.absolutePath) }
    }

    /** Whether [passed] and [reported] name the same folder, path for path. Pure but for the paths it builds. */
    internal fun sameFolder(passed: String?, reported: String?): Boolean =
        !passed.isNullOrBlank() && !reported.isNullOrBlank() && File(passed).absolutePath == File(reported).absolutePath

    /** Whether [file] is [folder] or under it. */
    internal fun inside(file: File, folder: File): Boolean {
        val path = file.absolutePath
        val root = folder.absolutePath.trimEnd('/')
        return path == root || path.startsWith("$root/")
    }

    /**
     * Whether Enginehost would take [path] as a `saveRoot` (its contract, tracker#580): absolute, in shared storage
     * (`/storage/emulated/<user>/...`, `/sdcard/...`) or on a removable volume (`/storage/<volume>/...`), not a
     * volume's root, and not inside `Android/data` or `Android/obb`. Pure.
     */
    internal fun acceptablePath(path: String): Boolean {
        if (!path.startsWith("/")) return false
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (".." in parts) return false
        val below = when {
            parts.firstOrNull() == "sdcard" -> parts.drop(1)
            parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" -> parts.drop(3)
            parts.size >= 2 && parts[0] == "storage" && parts[1] != "self" -> parts.drop(2)
            else -> return false
        }
        if (below.isEmpty()) return false
        val android = below.indexOfFirst { it.equals("Android", ignoreCase = true) }
        val area = below.getOrNull(android + 1)
        return !(android == 0 && (area.equals("data", ignoreCase = true) || area.equals("obb", ignoreCase = true)))
    }

    /** Whether files can be written in [folder], made when missing: a file is made in it and removed. */
    internal fun writable(folder: File): Boolean {
        if (!folder.isDirectory && !folder.mkdirs()) return false
        val probe = runCatching { File.createTempFile(".droidtop-", ".probe", folder) }.getOrNull() ?: return false
        return probe.delete()
    }

    /**
     * Copies the save files under [from] into [into] at the same relative paths, each one that is missing there or
     * older, to [MAX_DEPTH] levels; links are not followed. The number copied, or -1 when [from] cannot be read.
     */
    internal fun adopt(from: File, into: File): Int {
        val base = runCatching { from.canonicalFile.toPath() }.getOrNull() ?: return -1
        val files = runCatching {
            java.nio.file.Files.walk(base, MAX_DEPTH).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                    .collect(java.util.stream.Collectors.toList())
            }
        }.getOrNull() ?: return -1
        var copied = 0
        for (path in files) {
            val source = path.toFile()
            val target = File(into, base.relativize(path).toString())
            if (target.isFile && target.lastModified() >= source.lastModified()) continue
            val ok = runCatching {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
                target.setLastModified(source.lastModified())
            }.isSuccess
            if (ok) copied++
        }
        return copied
    }
}
