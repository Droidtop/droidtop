package dev.droidtop.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.droidtop.library.LibraryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launching one library entry, with no mode attached.
 *
 * This is how a surface outside Gaming reaches the shared launch
 * resolution (docs/SPEC.md, "Modes and what each contributes"): a game
 * pinned to the home screen from [LauncherGamesActivity] lands here, and
 * droidtop resolves its runner -- emulator, enginehost, Wine -- exactly
 * as the Gaming shell would, without
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
            report(this, "No game asked for")
            finish()
            return
        }
        val library = LibraryCore.library(applicationContext)
        val app = applicationContext
        launchScope.launch {
            val entry = runCatching { library.scanAll() }.getOrNull()
                ?.firstOrNull { it.id == entryId }
            if (entry == null) {
                withContext(Dispatchers.Main) { report(app, "No game with id $entryId") }
                return@launch
            }
            dispatch(app, entry)
        }
        finish()
    }

    companion object {
        private const val TAG = "droidtop.GameLaunch"
        const val ACTION_LAUNCH_GAME = "dev.droidtop.app.action.LAUNCH_GAME"
        const val EXTRA_ENTRY_ID = "dev.droidtop.app.extra.ENTRY_ID"

        /**
         * A launch outlives whichever screen asked for it (this Activity
         * finishes at once; the Games screen is left as the game comes
         * up), so it cannot hang off a lifecycle scope; the shared core
         * outlives them all.
         */
        private val launchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * The one way a surface outside Gaming launches an entry it
         * already holds: [dev.droidtop.library.Library.launch], with a
         * failure logged and shown rather than swallowed. The Launcher's
         * Games screen calls this directly; a pinned game icon comes
         * through [intentFor] and this Activity.
         */
        fun dispatch(context: Context, entry: LibraryEntry) {
            val app = context.applicationContext
            val library = LibraryCore.library(app)
            launchScope.launch {
                runCatching { library.launch(entry) }.onFailure {
                    android.util.Log.e(TAG, "Launch of ${entry.title} failed", it)
                    withContext(Dispatchers.Main) { report(app, "${entry.title} could not be launched") }
                }
            }
        }

        /** What a pinned game icon carries: this Activity, and the entry's id. */
        fun intentFor(context: Context, entryId: String): Intent =
            Intent(ACTION_LAUNCH_GAME)
                .setClassName(context.packageName, GameLaunchActivity::class.java.name)
                .putExtra(EXTRA_ENTRY_ID, entryId)

        private fun report(context: Context, message: String) {
            android.util.Log.w(TAG, message)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
