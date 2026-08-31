package dev.droidtop.app.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Registers :app's settings-catalog screens into the process-wide
 * [dev.droidtop.library.settings.SettingsScreenRegistry] at process
 * start. A manifest-declared ContentProvider's onCreate runs before ANY
 * Activity in the process -- including :shell-default's SettingsActivity,
 * which renders catalogs but cannot depend on :app -- so every surface
 * can resolve these screens by id without an initialization race and
 * without a dependency edge that can't exist. Provides no actual
 * content; the provider mechanism is only the earliest real, ordered
 * process-start hook Android offers an app module.
 */
class SettingsCatalogInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        AppSettingsCatalogs.ensureRegistered()
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
