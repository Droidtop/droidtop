package dev.droidtop.library.settings

import android.content.Context

/**
 * "Rescan library", as one action every screen that offers it runs: Gaming's
 * Settings, Game folders (in Gaming, in the launcher's settings and inside
 * the Launcher's Games grid) and the Games section's options menu.
 *
 * The library itself lives in :app, which this module cannot depend on, so
 * :app registers [handler] at process start. The handler walks the library
 * again, waits for the walk to finish and returns the sentence the person
 * reads. The row used to relaunch the Gaming shell with a rescan flag and
 * say nothing at all, so a rescan could not be told from a tap that missed
 * (rig, dq-shell2-02).
 */
object LibraryRescan {
    @Volatile
    var handler: (suspend (Context, (String) -> Unit) -> String)? = null

    suspend fun run(context: Context, onStatus: (String) -> Unit): String =
        handler?.invoke(context, onStatus) ?: "The library cannot be rescanned from here."
}
