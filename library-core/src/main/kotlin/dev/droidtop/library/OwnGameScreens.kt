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

        /**
         * Controller mouse mode (docs/SPEC.md 5b, Droidtop/tracker#565): on or off for this screen's game, or null
         * where the screen has no pointer to drive. The Quick Menu's Game section shows and turns it.
         */
        val padMouse: Boolean? get() = null

        fun setPadMouse(on: Boolean) {}

        /**
         * Report a problem (docs/SPEC.md 7e2; from the Quick Menu, owner 2026-10-28, Droidtop/tracker#601): whether
         * this screen can make a report of its running game, and makes one, ending in the share sheet. [done] gets the
         * line to say, on any thread. Never a link to the tracker, which is private.
         */
        val canReport: Boolean get() = false

        fun reportProblem(done: (String) -> Unit) {}

        /** The game's pointer, which mouse mode and the companion's trackpad both drive; null where there is none. */
        val pointer: Pointer? get() = null
    }

    /**
     * The one way into a game screen's pointer (Droidtop/tracker#565): relative moves in the game's own pixels, the
     * buttons, and wheel steps. Controller mouse mode and the companion's Keys trackpad both go through it, and it goes
     * through the calls the screen's touch trackpad already makes. Main thread.
     */
    interface Pointer {
        /** The display the game is drawn on, so the companion drives it only while that is the screen it types to. */
        val displayId: Int?

        /** The game's own screen width, in its pixels: what a trackpad's travel is scaled to. */
        val widthPx: Int

        fun move(dx: Int, dy: Int)

        fun button(button: PointerButton, pressed: Boolean)

        /** One wheel step, down (towards the person) or up. */
        fun wheel(down: Boolean)
    }

    enum class PointerButton { LEFT, RIGHT, MIDDLE }

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
