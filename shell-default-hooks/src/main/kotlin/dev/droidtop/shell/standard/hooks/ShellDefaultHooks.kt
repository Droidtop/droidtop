package dev.droidtop.shell.standard.hooks

import android.content.Context
import app.murinelauncher.hooks.ActionHooks
import app.murinelauncher.hooks.MurineHooks
import app.murinelauncher.hooks.NoticeHooks
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices
import dev.droidtop.runtime.CompanionSheet
import dev.droidtop.runtime.keyboard.AccessibilityHost
import dev.droidtop.shell.standard.ClearAllApps
import dev.droidtop.shell.standard.DroidtopScreens
import dev.droidtop.shell.standard.LauncherSearch
import dev.droidtop.shell.standard.ModeSwitcher

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
            "OPEN_SEARCH" to { c -> LauncherSearch.open(c) },
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
}
