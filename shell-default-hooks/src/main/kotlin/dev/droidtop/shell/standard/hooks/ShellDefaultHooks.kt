package dev.droidtop.shell.standard.hooks

import android.content.Context
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
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices
import dev.droidtop.library.settings.PluginShellHooks
import dev.droidtop.runtime.CompanionSheet
import dev.droidtop.runtime.LauncherSelfFilter
import dev.droidtop.runtime.keyboard.AccessibilityHost
import com.android.launcher3.Launcher
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.ClearAllApps
import dev.droidtop.shell.standard.DroidtopScreens
import dev.droidtop.shell.standard.HomeHints
import dev.droidtop.shell.standard.HomeRolePrefs
import dev.droidtop.shell.standard.LauncherSearch
import dev.droidtop.shell.standard.ModeSwitcher
import dev.droidtop.shell.standard.StandardPadHints
import dev.droidtop.ui.shared.MenuViews

/**
 * Installs droidtop's implementations of the Murine launcher's hooks (app.murinelauncher.hooks, Droidtop/tracker#921
 * item 2). Called once from DroidtopApplication.onCreate, after LauncherApplication has attached Murine's defaults.
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
}
