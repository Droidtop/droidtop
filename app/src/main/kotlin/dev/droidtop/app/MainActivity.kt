package dev.droidtop.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import dev.droidtop.library.Library
import dev.droidtop.library.NativeAppProvider
import dev.droidtop.shell.desktop.DesktopShell
import dev.droidtop.shell.gamepad.GamepadShell
import dev.droidtop.shell.standard.BackButtonMenu

/**
 * Not an app-drawer entry point — droidtop defaults to the normal Android
 * home-screen experience (`com.android.launcher3.Launcher`, forked in as
 * `:shell-default`'s "Standard" shell; see that module's README and its own
 * AndroidManifest.xml for the real HOME/LAUNCHER intent-filter). This
 * Activity only ever gets started by explicit Intent from a long-press of
 * the back key (`BackButtonMenu`, wired into both `Launcher` and here — see
 * that class's own doc comment), carrying [BackButtonMenu.EXTRA_MODE] to say
 * which of the two non-Standard shells to render.
 *
 * :shell-desktop needs a live HostBridge/DisplayOutput to show anything but
 * its own "no desktop session" placeholder — DesktopSessionService (which is
 * supposed to create the primary container and connect one) is still a TODO
 * stub, so `null`/`null` is passed here for now. See shell-desktop/README.md.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val library = Library(listOf(NativeAppProvider(applicationContext)))
        val mode = intent.getStringExtra(BackButtonMenu.EXTRA_MODE)

        setContent {
            when (mode) {
                BackButtonMenu.MODE_HANDHELD -> GamepadShell(library)
                else -> DesktopShell(library, hostBridge = null, primaryOutput = null)
            }
        }
    }

    // Same long-press-of-back shell switcher as Launcher — see
    // BackButtonMenu's doc comment for why long-press rather than a plain
    // back press (which keeps doing its normal job, here just finishing
    // this Activity).
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            BackButtonMenu.show(this)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }
}
