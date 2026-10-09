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
    private const val KEY_PRINTING = "droidtop_desktop_printing"
    private const val KEY_MICROPHONE = "droidtop_desktop_microphone"
    private const val KEY_ALL_LANGUAGE_FONTS = "droidtop_desktop_all_language_fonts"

    /**
     * Whether the primary's plan installs fonts for every script
     * (CompositorProvisioning, Droidtop/tracker#390). On by default: a
     * library full of Japanese file names is unreadable without them.
     */
    fun allLanguageFonts(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ALL_LANGUAGE_FONTS, true)

    fun setAllLanguageFonts(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ALL_LANGUAGE_FONTS, on).apply()
    }

    /** Whether the primary's provisioning plan includes CUPS (docs/SPEC.md 4b). */
    fun printing(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PRINTING, false)

    fun setPrinting(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_PRINTING, on).apply()
    }

    /** Whether the person opted into bridging the device microphone into the desktop (docs/SPEC.md 3d). */
    fun microphone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_MICROPHONE, false)

    fun setMicrophone(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_MICROPHONE, on).apply()
    }

    /** Whether a desktop image has been chosen: without one no session can start (Droidtop/tracker#370). */
    fun isSetUp(context: Context): Boolean = preferredPrimaryImageId(context) != null

    /**
     * The one way into Desktop setup: onboarding's DESKTOP_SETUP step on its own, which finishes when the
     * step is answered. Settings' "Desktop setup" row and the desktop's own not-set-up and failed pages open it.
     */
    fun setupIntent(context: Context): android.content.Intent =
        android.content.Intent(context, OnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_START_STEP, "DESKTOP_SETUP")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

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
