package dev.droidtop.library

import android.content.Context
import android.content.Intent

/**
 * A game screen that is droidtop's own (a Windows game's `WineGameActivity`),
 * which the shell's Quick Menu cannot reach through Android: its package is
 * droidtop, so the task manager neither force-stops nor lists it
 * (docs/SPEC.md, "The task manager"). The screen registers itself here while it
 * runs, and the Quick Menu's Game section reaches it through this one object:
 * Back opens the menu ([shellIntent]), Resume brings the screen back
 * ([Screen.entryId]), Stop ends it ([stop], called by [Library.quitRunning]).
 */
object OwnGameScreens {
    /** MainActivity lives in `:app`, which no game module can name; this is its class name. */
    private const val MAIN_ACTIVITY = "dev.droidtop.app.MainActivity"

    /** Asks the Gaming shell to open its Quick Menu (on the Game section while a game is running). */
    const val EXTRA_QUICK_MENU = "dev.droidtop.app.EXTRA_GAMING_QUICK_MENU"

    /** The same string as `BackButtonMenu.EXTRA_DISPLAY_REINIT`: the shell is entered without reclaiming the running game. */
    private const val EXTRA_KEEP_RUNNING = "dev.droidtop.app.EXTRA_DISPLAY_REINIT"

    interface Screen {
        /** The library game this screen runs; null when it is not one. */
        val entryId: String?

        /** Ends the game and closes the screen. Callable from any thread; the work is done off the main one. */
        fun stop()
    }

    @Volatile
    var current: Screen? = null
        private set

    fun register(screen: Screen) {
        current = screen
    }

    fun unregister(screen: Screen) {
        if (current === screen) current = null
    }

    /** Whether the game of [entryId] is the one running now, so launching it again must resume it, not start a second. */
    fun isRunning(entryId: String?): Boolean = entryId != null && current?.entryId == entryId

    /**
     * Ends the running screen, if there is one. True when there was one. Clears the
     * registration first, so a relaunch that follows (Restart) starts a new game.
     */
    fun stop(): Boolean {
        val screen = current ?: return false
        current = null
        screen.stop()
        return true
    }

    /** The shell, to the front with its Quick Menu open; the game behind it keeps running. */
    fun shellIntent(context: Context): Intent =
        Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MAIN_ACTIVITY)
            putExtra(EXTRA_QUICK_MENU, true)
            putExtra(EXTRA_KEEP_RUNNING, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
