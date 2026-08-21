package dev.droidtop.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.droidtop.library.JoiPlayGameProvider
import dev.droidtop.library.Library
import dev.droidtop.library.NativeAppProvider
import dev.droidtop.shell.desktop.DesktopShell
import dev.droidtop.shell.gamepad.GamepadShell
import dev.droidtop.shell.standard.BackButtonMenu
import java.io.File

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
 * Desktop mode starts [DesktopSessionService] and observes its
 * [DesktopSessionService.state] instead of passing a hardcoded null
 * HostBridge/DisplayOutput — real wiring, but the session itself is still
 * expected to land in [DesktopSessionState.Failed] on any real device right
 * now (see that service's own doc comment for the two concrete gaps: no
 * primary container image exists yet, and the non-root runtime is
 * unimplemented). `:shell-desktop`'s own "no desktop session" placeholder
 * only ever covered the *idle* case; `DesktopShell` doesn't yet render
 * Connecting/Failed distinctly — see shell-desktop/README.md.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Placeholder games root -- app-private external storage, chosen only
        // because it needs no runtime permission. Not where a user would
        // naturally put ROM/game folders; a real SAF-based folder picker
        // (same mechanism as the ES-DE library sync in docs/SPEC.md §7b)
        // is the real design, not built yet.
        val gamesRoot = File(getExternalFilesDir(null), "games").apply { mkdirs() }
        val library = Library(
            listOf(
                NativeAppProvider(applicationContext),
                JoiPlayGameProvider(applicationContext, gamesRoot),
            ),
        )
        val mode = intent.getStringExtra(BackButtonMenu.EXTRA_MODE)

        if (mode != BackButtonMenu.MODE_HANDHELD) {
            startForegroundService(Intent(this, DesktopSessionService::class.java))
        }

        setContent {
            when (mode) {
                BackButtonMenu.MODE_HANDHELD -> GamepadShell(library)
                else -> {
                    val sessionState by DesktopSessionService.state.collectAsState()
                    val connected = sessionState as? DesktopSessionState.Connected
                    DesktopShell(
                        library = library,
                        hostBridge = connected?.hostBridge,
                        primaryOutput = connected?.primaryOutput,
                    )
                }
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
