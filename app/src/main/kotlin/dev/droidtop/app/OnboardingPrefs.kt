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
    private const val KEY_GAMES_ROOT_PATH = "droidtop_games_root_path"
    private const val KEY_GAMES_ROOT_URI = "droidtop_games_root_uri"

    fun markOnboardingComplete(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ONBOARDING_COMPLETE, true)
            .apply()
    }

    /**
     * Best-effort resolution of a SAF tree [Uri] (from
     * `ACTION_OPEN_DOCUMENT_TREE`) to a real filesystem [File] path, using
     * the well-known (if unofficial -- SAF deliberately doesn't guarantee a
     * URI maps back to a plain path) trick of parsing the tree document ID
     * for a "primary:<relative path>" shape, which is what Android's own
     * external-storage document provider uses for the device's main shared
     * storage. Returns null for anything else (an SD card, a cloud
     * provider, etc.) -- [GameEngineDetector]/[JoiPlayGameProvider] work on
     * `java.io.File` today, not `DocumentFile`, so a folder that can't
     * resolve to a real path isn't usable yet. A real SAF-based rework
     * (scanning via ContentResolver/DocumentFile instead of File) is the
     * proper fix for that gap, not attempted here.
     */
    fun resolvePrimaryStoragePath(treeUri: Uri): File? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (t: Throwable) {
            return null
        }
        val split = docId.split(":", limit = 2)
        if (split.size != 2 || !split[0].equals("primary", ignoreCase = true)) return null
        val relativePath = split[1]
        return File(Environment.getExternalStorageDirectory(), relativePath)
    }

    /** [resolvedPath] is what [resolvePrimaryStoragePath] returned, if anything -- [treeUri] is always saved regardless, for a future SAF-based rework to pick back up. */
    fun saveGamesRoot(context: Context, treeUri: Uri, resolvedPath: File?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_GAMES_ROOT_URI, treeUri.toString())
            if (resolvedPath != null) putString(KEY_GAMES_ROOT_PATH, resolvedPath.absolutePath)
            apply()
        }
    }

    fun gamesRootPath(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_GAMES_ROOT_PATH, null)
}
