package dev.droidtop.shell.standard.hooks

import android.content.Context
import app.murinelauncher.hooks.MurineHooks
import app.murinelauncher.hooks.NoticeHooks
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices

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
    }
}
