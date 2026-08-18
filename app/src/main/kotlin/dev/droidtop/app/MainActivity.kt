package dev.droidtop.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts [dev.droidtop.shell.default.DefaultShell] by default. Which shell is
 * active (default vs. gamepad-console) is a user setting read here, not a
 * build-time choice — both read the same dev.droidtop.library.Library.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: instantiate Library + providers, select shell, set content
    }
}
