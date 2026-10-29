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
    private const val KEY_PANEL = "droidtop_desktop_panel"
    private const val KEY_TILE_WINDOWS = "droidtop_desktop_tile_windows"
    private const val KEY_KEEP_RUNNING = "droidtop_desktop_keep_running"
    /**
     * Whether the person chose. Not "droidtop_desktop_keep_running_answered": up to 0.2.0-dev.2398 the offer, asked
     * before any surface could show it, stored an instant Cancel under that key as an answer nobody gave
     * (Droidtop/tracker#389), so it is ignored and everyone is asked once more.
     */
    private const val KEY_KEEP_RUNNING_ANSWERED = "droidtop_desktop_keep_running_chosen"

    /**
     * Whether leaving Desktop for another mode keeps the desktop running in
     * the background (Droidtop/tracker#389). Off by default: a desktop
     * nobody sees still holds its memory, its processes and the audio
     * bridge's open output.
     */
    fun keepRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_RUNNING, false)

    /**
     * Sets [keepRunning] and records that the person has answered, so leaving Desktop no longer offers it
     * ([leaveAction]). The Settings switch and the offer's two buttons are both answers.
     */
    fun setKeepRunning(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_KEEP_RUNNING, on)
            .putBoolean(KEY_KEEP_RUNNING_ANSWERED, true)
            .apply()
    }

    /** Whether the person has chosen either way, in Settings or in the offer on leaving Desktop. */
    fun keepRunningAnswered(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_RUNNING_ANSWERED, false)

    /** What leaving Desktop for another mode does to the desktop session (Droidtop/tracker#389). */
    enum class LeaveAction { STOP, KEEP, OFFER }

    /**
     * The rule for leaving Desktop. Pure. A session that is not live has nothing to keep, so it stops as
     * before. A person who has chosen gets their choice. A person who never chose, leaving a live desktop,
     * is asked once: the desktop keeps running until they answer, and an answer of Stop (or no surface to
     * ask on) stops it then, as leaving always did.
     */
    fun leaveAction(sessionLive: Boolean, keepRunning: Boolean, answered: Boolean): LeaveAction = when {
        !sessionLive -> LeaveAction.STOP
        keepRunning -> LeaveAction.KEEP
        answered -> LeaveAction.STOP
        else -> LeaveAction.OFFER
    }

    /** The panel and launcher the sway desktop starts (dev.droidtop.runtime.DesktopPanel, Droidtop/tracker#353). */
    fun panel(context: Context): dev.droidtop.runtime.DesktopPanel =
        dev.droidtop.runtime.DesktopPanel.fromId(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PANEL, null))

    fun setPanel(context: Context, panel: dev.droidtop.runtime.DesktopPanel) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_PANEL, panel.id).apply()
    }

    /**
     * Whether sway tiles new windows instead of floating them (dev.droidtop.runtime.DesktopPanel.swayConfig,
     * Droidtop/tracker#686). Off by default: floating is what a finger can move and resize.
     */
    fun tileWindows(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_TILE_WINDOWS, false)

    fun setTileWindows(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_TILE_WINDOWS, on).apply()
    }

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
