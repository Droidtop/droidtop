package dev.droidtop.app

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * The user's chosen PRIMARY-catalog entry id (`known-image-repositories.json`),
 * set during onboarding's `DESKTOP_SETUP` step or its Settings re-entry
 * point, and the entry the existing primary container was actually made
 * from. Read by [DesktopSessionService], which reuses the existing primary
 * while the two agree and recreates it when the user has chosen another
 * image. Same shared prefs file every other droidtop pref (`GamesRootPrefs`,
 * `Modes`) already uses.
 */
object DesktopSetupPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_PRIMARY_IMAGE_ID = "droidtop_desktop_primary_image_id"
    private const val KEY_PRIMARY_CREATED_FROM = "droidtop_desktop_primary_created_from"

    fun preferredPrimaryImageId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PRIMARY_IMAGE_ID, null)

    fun setPreferredPrimaryImageId(context: Context, id: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .apply { if (id.isNullOrBlank()) remove(KEY_PRIMARY_IMAGE_ID) else putString(KEY_PRIMARY_IMAGE_ID, id) }
            .apply()
    }

    /** The catalog entry id the current primary container was created from, or null if none was recorded. */
    fun primaryCreatedFrom(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PRIMARY_CREATED_FROM, null)

    fun setPrimaryCreatedFrom(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PRIMARY_CREATED_FROM, id)
            .apply()
    }
}
