package dev.droidtop.library.settings

import android.content.Context
import android.content.Intent

/**
 * The places things live (docs/SPEC.md 7j "Places", Droidtop/tracker#258, #346): Stores, Social,
 * Downloads and Updates, and Plugins. Each is a registered settings catalog screen ([screenId],
 * registered by `:app`), and this is the one list of them for every mode: Gaming's left menu lists them
 * as sections, Standard's settings root and droidtop's icon shortcuts link to them, Desktop's Start menu
 * lists them. Kiosk and Kid hide them in every mode ([visible]), because they are the device's management
 * as much as Settings is. Updates is a place of its own (its links and notifications open it) but not
 * [listed]: it is a page of Downloads and Updates (owner, 2026-10-24, Droidtop/tracker#639: "Updates should
 * be in the Downloads and Updates menu").
 */
private const val STORES_SCREEN = "stores"
private const val SOCIAL_SCREEN = "social"
private const val DOWNLOADS_SCREEN = "plugin_jobs"
private const val UPDATES_SCREEN = "updates"
private const val PLUGINS_SCREEN = "plugins"

enum class Place(val screenId: String, val title: String, val listed: Boolean = true) {
    // "Game sources" (Droidtop/tracker#397 slice E): stores, game folders and imports, one place.
    STORES(STORES_SCREEN, "Game sources"),
    SOCIAL(SOCIAL_SCREEN, "Social"),
    DOWNLOADS(DOWNLOADS_SCREEN, "Downloads and Updates"),
    // A page of Downloads and Updates, reached by its "Updates · N available" row there, never a row of its own (the owner,
    // 2026-10-30, Droidtop/tracker#792: "An updates link should be in the Downloads and Updates tab. Not there directly,
    // but a shortcut").
    UPDATES(UPDATES_SCREEN, "Updates", listed = false),
    PLUGINS(PLUGINS_SCREEN, "Plugins"),
    ;

    companion object {
        // The registry ids, as constants so code that names one screen (the Quick Menu's
        // "Manage plugins") can use them where a constant is needed. The enum entries use the
        // top-level constants because an entry cannot read its own companion while it is initialised.
        const val ID_STORES = STORES_SCREEN
        const val ID_SOCIAL = SOCIAL_SCREEN
        const val ID_DOWNLOADS = DOWNLOADS_SCREEN
        const val ID_UPDATES = UPDATES_SCREEN
        const val ID_PLUGINS = PLUGINS_SCREEN

        fun byScreenId(screenId: String?): Place? = entries.firstOrNull { it.screenId == screenId }

        /** The places a UI mode lists: every [listed] one, or none where it hides Settings (Kiosk, Kid). */
        fun visible(uiMode: UiMode): List<Place> = reachable(uiMode).filter { it.listed }

        /** The places a UI mode lets a link or a row open, listed or not: all, or none in Kiosk and Kid. */
        fun reachable(uiMode: UiMode): List<Place> = if (uiMode.hidesSettings) emptyList() else entries

        /**
         * Whether a link to a place (a notification) should open it in Gaming: only when Gaming is the
         * mode in use ([lastModeId], [Modes.lastMode]) and still on. Anywhere else it opens in the screen
         * host ([CatalogScreenLink]), so a Standard or Desktop user is not sent into a mode they are
         * not using, or into nothing when Gaming is off.
         */
        fun opensInGaming(lastModeId: String?, enabled: Set<Mode>): Boolean =
            lastModeId == Mode.GAMING.id && Mode.GAMING in enabled

        /** Opens [place] in droidtop's screen host, outside any shell. */
        fun openIntent(context: Context, place: Place): Intent = CatalogScreenLink.intent(context, place.screenId)
    }
}

/**
 * The one way to show a registered catalog screen on its own, outside a shell: `:app`'s screen host
 * answers [ACTION] with the screen named by [EXTRA_SCREEN_ID]. Shells and modules that cannot depend on
 * `:app` (`:shell-desktop`, `:shell-default`) open screens through it: the places, the container manager.
 */
object CatalogScreenLink {
    const val ACTION = "dev.droidtop.app.action.OPEN_SCREEN"
    const val EXTRA_SCREEN_ID = "dev.droidtop.app.extra.SCREEN_ID"

    /** The one argument the screen opens at ([SettingsScreenRegistry.get]'s deep link): a plugin's id on Plugins. */
    const val EXTRA_DEEP_LINK = "dev.droidtop.app.extra.DEEP_LINK"

    fun intent(context: Context, screenId: String, deepLink: String? = null): Intent = Intent(ACTION)
        .setPackage(context.packageName)
        .putExtra(EXTRA_SCREEN_ID, screenId)
        .apply { if (deepLink != null) putExtra(EXTRA_DEEP_LINK, deepLink) }
        // The host runs in a task of its own; each opening starts it fresh, so a screen left open
        // never answers for the one asked for (the same rule as Settings, SPEC 2c).
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}
