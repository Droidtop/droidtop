package dev.droidtop.shell.standard.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import app.murinelauncher.hooks.ActionHooks
import app.murinelauncher.hooks.AppHooks
import app.murinelauncher.hooks.HomeHooks
import app.murinelauncher.hooks.MenuHooks
import app.murinelauncher.hooks.MurineHooks
import app.murinelauncher.hooks.NoticeHooks
import app.murinelauncher.hooks.SearchHooks
import app.murinelauncher.hooks.SettingsHooks
import app.murinelauncher.hooks.ThemeHooks
import app.murinelauncher.theme.ThemeOverride
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices
import dev.droidtop.library.settings.Place
import dev.droidtop.library.settings.PluginShellHooks
import dev.droidtop.library.settings.UiModePrefs
import dev.droidtop.runtime.CompanionSheet
import dev.droidtop.runtime.LauncherSelfFilter
import dev.droidtop.runtime.keyboard.AccessibilityHost
import dev.droidtop.runtime.keyboard.AccessibilityKeyboard
import com.android.launcher3.Launcher
import com.android.launcher3.settings.SettingsActivity
import dev.droidtop.library.settings.AppearancePrefs
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.ClearAllApps
import dev.droidtop.shell.standard.DroidtopScreens
import dev.droidtop.shell.standard.HomeHints
import dev.droidtop.shell.standard.HomeRolePrefs
import dev.droidtop.shell.standard.LauncherImportActivity
import dev.droidtop.shell.standard.LauncherSearch
import dev.droidtop.shell.standard.ModeSwitcher
import dev.droidtop.shell.standard.StandardPadHints
import dev.droidtop.shell.standard.settings.SettingsDesktopFragment
import dev.droidtop.shell.standard.settings.SettingsGamingFragment
import dev.droidtop.shell.standard.settings.SettingsGlobalFragment
import dev.droidtop.shell.standard.settings.drawableRes
import dev.droidtop.ui.shared.MenuViews
import com.android.launcher3.R as LauncherR

/**
 * Installs droidtop's implementations of the Murine launcher's hooks (app.murinelauncher.hooks, Droidtop/tracker#921
 * item 2). Called once from DroidtopApplication.onCreate before LauncherApplication's own start, so the launcher's start
 * (its light or dark, ThemeOverride) already reads droidtop's. It only sets references, so it is safe in every process.
 */
object ShellDefaultHooks {
    fun install(context: Context) {
        val app = context.applicationContext
        // Murine's short messages are droidtop's flash notices, falling back to a toast with no droidtop surface on
        // screen, as they did when Murine called Notices.flash itself.
        MurineHooks.notices = NoticeHooks { message, tone ->
            Notices.flash(message, tone = if (tone == NoticeHooks.Tone.Caution) NoticeTone.Caution else NoticeTone.Info, context = app)
        }
        MurineHooks.actions = DroidtopActions
        MurineHooks.menus = DroidtopMenus
        // The launcher's one search is droidtop's library search (docs/SPEC.md "Launcher search").
        MurineHooks.search = SearchHooks { c, query -> LauncherSearch.open(c, query.orEmpty()) }
        // droidtop is the launcher's own package: its one icon and its widgets show (docs/SPEC.md 2c).
        MurineHooks.apps = AppHooks { className, isWidgetProvider -> LauncherSelfFilter.shown(className, isWidgetProvider) }
        MurineHooks.home = DroidtopHome
        MurineHooks.settings = DroidtopSettings
        MurineHooks.theme = DroidtopTheme
    }

    /**
     * droidtop's own home actions, by their GestureAction ids (the stored form: a saved slot or swipe keeps them), and
     * droidtop's one accessibility service for the launcher's global actions. Each opens what it opened when the
     * launcher called it directly.
     */
    private object DroidtopActions : ActionHooks {
        private val runners: Map<String, (Context) -> Unit> = mapOf(
            "CLEAR_ALL_APPS" to { c -> ClearAllApps.open(c) },
            "OPEN_COMPANION" to { c -> CompanionSheet.open(c, false) },
            "OPEN_QUICK_MENU" to { c -> DroidtopScreens.openQuickMenu(c) },
            "SWITCH_MODE" to { c -> ModeSwitcher.open(c) },
            "OPEN_GAMES" to { c -> DroidtopScreens.openGames(c) },
        )

        override fun offers(id: String) = id in runners

        override fun run(id: String, context: Context): Boolean {
            val runner = runners[id] ?: return false
            runner(context)
            return true
        }

        override fun performGlobalAction(action: Int) = AccessibilityHost.performGlobalAction(action)

        /**
         * droidtop's one accessibility service (Droidtop/tracker#740), turned on the one way: elevated access when
         * droidtop has it, else Android's accessibility page. Turning it on may wait on the privileged service, so it
         * runs off the main thread.
         */
        override fun requestAccessibility(context: Context): Boolean {
            if (AccessibilityKeyboard.isActive(context)) return true
            Thread { AccessibilityKeyboard.turnOn(context) }.start()
            return false
        }
    }

    /**
     * droidtop's menus: the launcher's small menus are the one anchored menu, in the colours outside Gaming
     * (MenuViews, docs/SPEC.md 7k, Droidtop/tracker#827). The home screen's long-press menu gets the library's games
     * grid, the Quick Menu (the sheet Gaming's R2 opens), the companion while the device has no second screen to hold
     * it (docs/SPEC.md "The companion's tabs", One screen), then plugins' entries (docs/plugin-api.md 1.9); an app's
     * long-press menu gets "Plugin actions" (Droidtop/tracker#67). Plugins' entries are answered from memory
     * (PluginShellHooks) and kept here by id until the next menu asks, since the menu runs one by its id.
     */
    private object DroidtopMenus : MenuHooks {
        @Volatile private var pluginEntries: Map<String, PluginShellHooks.MenuEntry> = emptyMap()

        override fun show(anchor: View, title: String, rows: List<MenuHooks.Row>, note: String?) {
            MenuViews.show(anchor, title, rows.map { MenuViews.Row(it.title, it.onSelect, it.checked) }, note)
        }

        override fun homeEntries(context: Context): List<MenuHooks.Entry> {
            val entries = mutableListOf(
                MenuHooks.Entry("OPEN_GAMES", context.getString(R.string.home_menu_games)),
                MenuHooks.Entry("OPEN_QUICK_MENU", context.getString(R.string.home_menu_quick_menu), "setting"),
            )
            if (CompanionSheet.offered(context)) {
                entries += MenuHooks.Entry("OPEN_COMPANION", context.getString(R.string.home_menu_companion))
            }
            val plugins = PluginShellHooks.homeMenu(context)
            remember(plugins.mapIndexed { i, entry -> "plugin-home:$i" to entry })
            plugins.forEachIndexed { i, entry -> entries += MenuHooks.Entry("plugin-home:$i", entry.label) }
            return entries
        }

        override fun appEntry(context: Context, packageName: String, label: String): MenuHooks.Entry? {
            val entry = PluginShellHooks.appMenu(context, packageName, label) ?: return null
            val id = "plugin-app:$packageName"
            remember(listOf(id to entry))
            return MenuHooks.Entry(id, entry.label)
        }

        override fun run(id: String, context: Context) {
            if (!DroidtopActions.run(id, context)) pluginEntries[id]?.run?.invoke(context)
        }

        private fun remember(entries: List<Pair<String, PluginShellHooks.MenuEntry>>) {
            val prefix = entries.firstOrNull()?.first?.substringBefore(':') ?: return
            pluginEntries = pluginEntries.filterKeys { !it.startsWith("$prefix:") } + entries
        }
    }

    /**
     * droidtop's work around the Standard home: as it shows, its own icon asked for at the end of setup is placed
     * (HomeRolePrefs), its in-place hint shows until done (HomeHints, Droidtop/tracker#829) and the pad's hint row is
     * laid out (StandardPadHints, Droidtop/tracker#699); keys and touches tell that row which input is in use; a long
     * Back opens the mode menu (BackButtonMenu).
     */
    private object DroidtopHome : HomeHooks {
        override fun onHomeShown(launcher: Launcher) {
            HomeRolePrefs.placePendingIcon(launcher)
            HomeHints.onHomeShown(launcher)
            StandardPadHints.onHomeShown(launcher)
        }

        override fun onKey(event: KeyEvent) = StandardPadHints.noteKey(event)

        override fun onTouch(event: MotionEvent) = StandardPadHints.noteTouch(event)

        override fun onBackLongPress(launcher: Launcher): Boolean {
            BackButtonMenu.show(launcher)
            return true
        }
    }

    /**
     * droidtop's rows on the launcher's settings: Global settings first (also the toolbar's action on every settings
     * screen; it was only that action once, and the rig found no way back to Modes after turning Gaming off,
     * dq-coordinator-23 F5), the places a person can reach in this UI mode (Standard has no left menu, docs/SPEC.md
     * 7j "Places in every mode"; none in Kiosk and Kid), and Desktop's and Gaming's settings. The launcher's Home
     * screen row opens Global settings, where "droidtop is your home screen" is (Droidtop/tracker#571 row 11); its
     * import row opens the Nova and Apex import (Droidtop/tracker#564); droidtop has the taskbar (StandardTaskbar).
     */
    private object DroidtopSettings : SettingsHooks {
        private const val PLACE = "place:"
        private const val HOME_SCREEN = "pref_home_screen"
        private const val LAUNCHER_IMPORT = "pref_launcher_import"
        private const val TASKBAR = "pref_taskbar"

        private fun global(context: Context) = SettingsHooks.Row(
            id = "global_settings",
            title = context.getString(R.string.pref_global_settings_title),
            summary = context.getString(R.string.pref_global_settings_desc),
            icon = LauncherR.drawable.ic_settings_general,
            fragment = SettingsGlobalFragment::class.java.name,
        )

        override fun rows(context: Context): List<SettingsHooks.Row> {
            val places = context.getString(R.string.pref_subcategory_places)
            val shells = context.getString(R.string.pref_subcategory_other_shells)
            return listOf(global(context)) +
                Place.visible(UiModePrefs.get(context)).map { place ->
                    SettingsHooks.Row(PLACE + place.screenId, place.title, icon = place.icon.drawableRes(), group = places)
                } +
                listOf(
                    SettingsHooks.Row(
                        "desktop_settings", context.getString(R.string.pref_category_desktop_title),
                        context.getString(R.string.pref_category_desktop_desc), R.drawable.ic_catalog_desktop,
                        shells, SettingsDesktopFragment::class.java.name,
                    ),
                    SettingsHooks.Row(
                        "gaming_settings", context.getString(R.string.pref_category_gaming_title),
                        context.getString(R.string.pref_category_gaming_desc), R.drawable.ic_catalog_gaming,
                        shells, SettingsGamingFragment::class.java.name,
                    ),
                )
        }

        override fun toolbarRow(context: Context) = global(context)

        override fun offers(key: String) = key == HOME_SCREEN || key == LAUNCHER_IMPORT || key == TASKBAR

        override fun run(id: String, context: Context) {
            val intent = when {
                id.startsWith(PLACE) -> Place.byScreenId(id.removePrefix(PLACE))?.let { Place.openIntent(context, it) }
                id == HOME_SCREEN -> Intent(context, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_FRAGMENT, SettingsGlobalFragment::class.java.name)
                id == LAUNCHER_IMPORT -> Intent(context, LauncherImportActivity::class.java)
                else -> null
            } ?: return
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * droidtop's one Appearance setting (AppearancePrefs, Settings > Appearance; docs/SPEC.md 7k, Droidtop/tracker#560)
     * is the launcher's light or dark, so the home screen never disagrees with droidtop's other screens.
     */
    private object DroidtopTheme : ThemeHooks {
        /** Held so the registration lives as long as the process (SharedPreferences keeps listeners weakly). */
        @Volatile private var watch: SharedPreferences.OnSharedPreferenceChangeListener? = null

        override fun choice(context: Context) = when (AppearancePrefs.choice(context)) {
            AppearancePrefs.LIGHT -> ThemeOverride.THEME_LIGHT
            AppearancePrefs.DARK -> ThemeOverride.THEME_DARK
            else -> ThemeOverride.THEME_SYSTEM
        }

        override fun watch(context: Context, onChange: Runnable) {
            watch = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == AppearancePrefs.KEY_APPEARANCE) onChange.run()
            }.also { CatalogPrefs.prefs(context).registerOnSharedPreferenceChangeListener(it) }
        }
    }
}
