package dev.droidtop.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.droidtop.library.LaunchResult
import dev.droidtop.library.LibraryEntry

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
 * No window of its own: it hands the id to
 * [dev.droidtop.library.Library.launchInBackground] (the game's record,
 * play history, launch-screen memory and error logging included, the same
 * as every other entry point), shows a refusal as a toast, and finishes.
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
        dispatch(this, entryId)
        finish()
    }

    companion object {
        private const val TAG = "droidtop.GameLaunch"
        const val ACTION_LAUNCH_GAME = "dev.droidtop.app.action.LAUNCH_GAME"
        const val EXTRA_ENTRY_ID = "dev.droidtop.app.extra.ENTRY_ID"

        /**
         * The one way a surface outside Gaming launches an entry:
         * [dev.droidtop.library.Library.launchInBackground], which runs in
         * the library's own scope (this Activity finishes at once, and the
         * Games screen is left as the game comes up) and finds the game
         * from its record rather than walking the library. A failure is
         * shown rather than swallowed. The Launcher's Games screen calls
         * this directly; a pinned game icon comes through [intentFor] and
         * this Activity.
         */
        fun dispatch(context: Context, entry: LibraryEntry) {
            dispatch(context, entry.id)
        }

        private fun dispatch(context: Context, entryId: String) {
            val app = context.applicationContext
            LibraryCore.library(app).launchInBackground(entryId) { result ->
                when (result) {
                    is LaunchResult.Refused ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post { report(app, result.reason) }
                    // Play history just moved, so the "Continue playing"
                    // widget (docs/SPEC.md Launcher mode) shows this game
                    // as most-recent now rather than at its next 30-minute
                    // system tick.
                    LaunchResult.Launched -> ContinuePlayingWidgetProvider.requestUpdate(app)
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
