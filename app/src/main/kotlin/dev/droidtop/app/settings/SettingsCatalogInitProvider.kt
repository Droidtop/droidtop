package dev.droidtop.app.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.droidtop.library.settings.Modes

/**
 * droidtop's process-start hook, and the shared core only.
 *
 * A manifest-declared ContentProvider's onCreate runs before Application.
 * onCreate and before ANY Activity in the process, so this is where the
 * mode snapshot is taken: everything downstream -- including
 * [dev.droidtop.app.ModeStartup], which runs from Application.onCreate --
 * asks [Modes] rather than reading preferences of its own.
 *
 * What runs here runs in every mode: the settings-catalog registration
 * (:shell-default's SettingsActivity renders these catalogs but cannot
 * depend on :app, so they must be registered before it can resolve a
 * screen by id), and the release probe. Anything a single mode owns is in
 * [dev.droidtop.app.ModeStartup] instead. Provides no content; the
 * provider mechanism is only the earliest ordered process-start hook
 * Android offers an app module.
 */
class SettingsCatalogInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        // First, because everything else asks it what to start. Also
        // where the one-time Handheld -> Gaming preference migration runs.
        Modes.load(appContext)
        AppSettingsCatalogs.ensureRegistered()
        // The at-most-daily release probe (one small unauthenticated
        // download, off switch in Settings > Software updates). Process
        // start is the honest trigger: droidtop is a launcher, so its
        // process starts roughly once per boot rather than per use.
        dev.droidtop.app.update.AppSelfUpdate.maybeCheck(appContext)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
