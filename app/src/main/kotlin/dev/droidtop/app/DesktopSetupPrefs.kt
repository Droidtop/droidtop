package dev.droidtop.app

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * The user's chosen PRIMARY-catalog entry id (`known-image-repositories.json`),
 * set during onboarding's `DESKTOP_SETUP` step or its Settings re-entry
 * point. Read by [DesktopSessionService.selectPrimaryImage] -- closes the
 * "no user-facing compositor-choice setting yet" gap that class's own doc
 * comment used to describe. Same shared prefs file every other droidtop
 * pref (`GamesRootPrefs`, `ModePrefs`) already uses.
 */
object DesktopSetupPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_PRIMARY_IMAGE_ID = "droidtop_desktop_primary_image_id"

    fun preferredPrimaryImageId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PRIMARY_IMAGE_ID, null)

    fun setPreferredPrimaryImageId(context: Context, id: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .apply { if (id.isNullOrBlank()) remove(KEY_PRIMARY_IMAGE_ID) else putString(KEY_PRIMARY_IMAGE_ID, id) }
            .apply()
    }
}
