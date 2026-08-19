package dev.droidtop.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import dev.droidtop.library.Library
import dev.droidtop.library.NativeAppProvider
import dev.droidtop.shell.standard.DefaultShell

/**
 * Hosts [dev.droidtop.shell.standard.DefaultShell] by default. Which shell is
 * active (default vs. gamepad-console) is a user setting read here, not a
 * build-time choice — both read the same dev.droidtop.library.Library.
 *
 * Only [NativeAppProvider] is wired in right now — the Wine/Linux-container/
 * remote-stream providers all depend on runtime pieces
 * (`runtime-linux-root`/`-noroot`'s container backends,
 * `runtime-windows`'s Wine sessions) that are still unimplemented TODOs, so
 * wiring them in here would just be dead code paths, not extra
 * functionality. This is genuinely everything that's real to show right now.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val library = Library(listOf(NativeAppProvider(applicationContext)))

        setContent {
            DefaultShell(library)
        }
    }
}
