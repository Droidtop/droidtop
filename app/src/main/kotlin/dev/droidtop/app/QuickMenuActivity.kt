package dev.droidtop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                QuickMenuStandalone(
                    library = library,
                    onOpenStartMenu = { finish() },
                    onDismiss = { finish() },
                )
            }
        }
    }
}
