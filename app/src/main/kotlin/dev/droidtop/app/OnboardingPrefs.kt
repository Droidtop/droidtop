package dev.droidtop.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Persists what [OnboardingActivity] collects, in the same shared prefs
 * file `:shell-default`'s settings already use ("com.android.launcher3.
 * prefs") so both sides read/write one consistent store without a compile
 * dependency between the modules (see OnboardingGate's own doc comment for
 * why that dependency doesn't exist).
 */
object GamesRootPrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
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

    fun removeGamesRoot(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_GAMES_ROOT_PATHS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_GAMES_ROOT_PATHS, current - path).apply()
    }

    fun gamesRootPaths(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(KEY_GAMES_ROOT_PATHS, emptySet()) ?: emptySet()
}
