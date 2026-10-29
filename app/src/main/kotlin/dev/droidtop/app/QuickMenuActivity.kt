package dev.droidtop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.Context
import android.content.Intent
import dev.droidtop.shell.gamepad.QuickSection
import dev.droidtop.shell.gamepad.hosted.QuickMenuStandalone

/**
 * The Quick Menu over Standard's home screen (docs/SPEC.md, Launcher mode "Gestures"; Droidtop/tracker#564): the
 * same sheet Gaming's R2 and Desktop's tray open, reached from a home-screen gesture set to Quick Menu. A
 * translucent window, so the home screen stays behind it. Standard has no Start menu, so Start closes it.
 *
 * The launcher reaches it by class name (`dev.droidtop.shell.standard.QuickMenuLink`), because `:shell-default`
 * cannot depend on `:app`.
 */
class QuickMenuActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = LibraryCore.library(applicationContext)
        val start = intent?.getStringExtra(EXTRA_SECTION)?.let { name -> QuickSection.entries.firstOrNull { it.name == name } }
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                QuickMenuStandalone(
                    library = library,
                    onOpenStartMenu = { finish() },
                    onDismiss = { finish() },
                    startSection = start,
                )
            }
        }
    }

    companion object {
        /** A [QuickSection] name: the tab the menu opens on. */
        private const val EXTRA_SECTION = "dev.droidtop.app.extra.QUICK_SECTION"

        /** The Quick Menu over whatever is in front, on [section]. */
        fun intent(context: Context, section: QuickSection): Intent = Intent(context, QuickMenuActivity::class.java)
            .putExtra(EXTRA_SECTION, section.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
