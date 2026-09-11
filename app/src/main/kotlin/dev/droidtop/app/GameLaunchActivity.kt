package dev.droidtop.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launching one library entry, with no mode attached.
 *
 * This is how a surface outside Gaming reaches the shared launch
 * resolution (docs/SPEC.md, "Modes and what each contributes"): the
 * Launcher can hand a game here and droidtop resolves its runner --
 * emulator, enginehost, Wine -- exactly as the Gaming shell would, without
 * the Gaming UI or any of its integrations. It is also the adb handle for
 * checking that claim on a rig:
 *
 *   adb shell am start -n dev.droidtop.app/.GameLaunchActivity \
 *     -e dev.droidtop.app.extra.ENTRY_ID '<entry id>'
 *
 * No window of its own: it resolves, dispatches through
 * [dev.droidtop.library.Library.launch] (play history, launch-screen
 * memory and error logging included, the same as every other entry point)
 * and finishes.
 */
class GameLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryId = intent?.getStringExtra(EXTRA_ENTRY_ID)
        if (entryId.isNullOrBlank()) {
            report("No game asked for")
            finish()
            return
        }
        // The Activity finishes immediately, so the launch cannot hang off
        // its lifecycle scope; the shared core outlives it.
        val library = LibraryCore.library(applicationContext)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val entry = runCatching { library.scanAll() }.getOrNull()
                ?.firstOrNull { it.id == entryId }
            if (entry == null) {
                withContext(Dispatchers.Main) { report("No game with id $entryId") }
                return@launch
            }
            runCatching { library.launch(entry) }.onFailure {
                android.util.Log.e(TAG, "Launch of ${entry.title} failed", it)
                withContext(Dispatchers.Main) { report("${entry.title} could not be launched") }
            }
        }
        finish()
    }

    private fun report(message: String) {
        android.util.Log.w(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "droidtop.GameLaunch"
        const val EXTRA_ENTRY_ID = "dev.droidtop.app.extra.ENTRY_ID"
    }
}
