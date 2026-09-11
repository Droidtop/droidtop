package dev.droidtop.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Persists what [OnboardingActivity] collects, in the same shared prefs
 * file `:shell-default`'s settings already use ("com.android.launcher3.
 * prefs") so both sides read/write one consistent store without a compile
 * dependency between the modules (see OnboardingGate's own doc comment for
 * why that dependency doesn't exist).
 */
object GamesRootPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_ONBOARDING_COMPLETE = "droidtop_onboarding_complete"
    // A Set, not a single value -- games/ROMs aren't necessarily all in one
    // folder (an SD card folder plus an internal one, say), and per
    // direction ROM support itself is opt-in, so this needs to hold zero,
    // one, or many roots equally well.
    private const val KEY_GAMES_ROOT_PATHS = "droidtop_games_root_paths"

    fun markOnboardingComplete(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ONBOARDING_COMPLETE, true)
            .apply()
    }

    /**
     * Best-effort resolution of a SAF tree [Uri] (from
     * `ACTION_OPEN_DOCUMENT_TREE`) to a real filesystem [File] path, using
     * the well-known (if unofficial -- SAF deliberately doesn't guarantee a
     * URI maps back to a plain path) trick of parsing the tree document
     * ID's "<volumeId>:<relative path>" shape. "primary" is the device's
     * main shared storage (Environment.getExternalStorageDirectory()); any
     * other volume ID is a removable SD card or similar, which on
     * essentially every real AOSP-based Android device mounts at
     * `/storage/<volumeId>/` -- confirmed real ROMs-on-SD-card usage is the
     * actual, common case for a handheld like this (not a hypothetical
     * edge case), so this is checked for and used, not skipped.
     *
     * The constructed path is verified to actually exist and be a
     * directory before being trusted -- if this device's real mount
     * convention differs, this returns null (same "can't resolve, fall
     * back to the raw SAF URI" behavior as before) instead of silently
     * pointing at a folder that isn't really there.
     *
     * [GameEngineDetector]/[EngineGameProvider] work on `java.io.File`
     * today, not `DocumentFile`; a folder that genuinely can't resolve to
     * a real path (an unusual mount layout, a cloud-backed provider) isn't
     * usable yet -- a real SAF-based rework (scanning via
     * ContentResolver/DocumentFile instead of File) is the proper
     * general fix for that, not attempted here.
     */
    fun resolveStoragePath(treeUri: Uri): File? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (t: Throwable) {
            return null
        }
        val split = docId.split(":", limit = 2)
        if (split.size != 2) return null
        val (volumeId, relativePath) = split

        val volumeRoot = if (volumeId.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volumeId")
        }
        val resolved = File(volumeRoot, relativePath)
        return if (resolved.isDirectory) resolved else null
    }

    /** Adds one resolved games root to the set. No-op if [resolvedPath] couldn't be resolved -- there's nothing usable to add in that case (see resolveStoragePath's own doc comment). */
    fun addGamesRoot(context: Context, resolvedPath: File) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_GAMES_ROOT_PATHS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_GAMES_ROOT_PATHS, current + resolvedPath.absolutePath).apply()
    }

    /**
     * Adds a games root the user typed as a plain filesystem path,
     * bypassing SAF entirely. Returns null when it was added, or a
     * user-facing reason it was not.
     *
     * Why this exists at all, when there is already a folder picker: the
     * picker can only offer what Android exposes as a SAF storage volume,
     * and real games live outside that set often enough to matter.
     *
     *  - An emulator's host share. BlueStacks mounts the Windows folder
     *    it shares at /mnt/windows/BstSharedFolder over vboxsf; it is
     *    readable, it is not a storage volume, and it is not mirrored
     *    under /sdcard, so on 2026-09-11 the Android 9 rig could not be
     *    pointed at the user's game library at all. The same is true of
     *    an SDK emulator folder pushed in over adb.
     *  - A rooted device's extra mounts: a second SD card bind-mounted
     *    somewhere of the user's choosing, a USB drive mounted by hand,
     *    an OTG disk under /mnt.
     *  - Anywhere [resolveStoragePath] gives up: a SAF tree URI whose
     *    volume does not follow the /storage/<volumeId> convention
     *    resolves to null and the folder is silently unusable. Typing the
     *    real path is the escape hatch from that.
     *
     * Validation is the same question the scanner will ask later, asked
     * now while the user is still looking at the screen: an absolute path
     * that exists, is a directory, and whose contents this process can
     * actually list. listFiles() returning null is the one check that
     * catches a path that exists but is unreadable for want of the
     * storage permission -- File.canRead() alone does not.
     */
    fun addGamesRootByPath(context: Context, rawPath: String): String? {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return "Type a folder path first"
        val file = File(trimmed)
        if (!file.isAbsolute) return "Use a full path, starting at /"
        val canonical = try {
            file.canonicalFile
        } catch (e: java.io.IOException) {
            return "Could not resolve $trimmed on this device"
        }
        if (!canonical.exists()) return "$trimmed does not exist"
        if (!canonical.isDirectory) return "$trimmed is a file, not a folder"
        if (canonical.listFiles() == null) {
            return "droidtop cannot read $trimmed -- grant storage access first, " +
                "or add it with the folder picker instead"
        }
        if (canonical.absolutePath in gamesRootPaths(context)) {
            return "$trimmed is already being scanned"
        }
        addGamesRoot(context, canonical)
        return null
    }

    fun removeGamesRoot(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_GAMES_ROOT_PATHS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_GAMES_ROOT_PATHS, current - path).apply()
    }

    fun gamesRootPaths(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(KEY_GAMES_ROOT_PATHS, emptySet()) ?: emptySet()
}
