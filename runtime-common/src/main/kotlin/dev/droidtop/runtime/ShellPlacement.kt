package dev.droidtop.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the shell (Gaming and Desktop's MainActivity) belongs, so it starts there once rather than twice
 * (Droidtop/tracker#824). A Home press in a fresh process knew nothing of the screens, started the shell on the
 * built-in screen, and the screen placement then moved it to the second screen with a second start 0.5 s later, over
 * whatever the first start had opened (What's new, on the console: "I don't have a "what's new" splash").
 *
 * - [remember] keeps the display the shell was last in front on, across processes; Home starts the shell there
 *   ([launchDisplay]), so the placement finds it already placed and starts nothing.
 * - [settled] is the placement's own word that the shell is where it belongs and no move is coming: what a screen
 *   that must not be covered (What's new) waits for, instead of a timer.
 */
object ShellPlacement {
    private const val PREFS = "shell_placement"
    private const val KEY_DISPLAY = "display_id"

    private val settledFlow = MutableStateFlow(false)

    /** True once the placement's last pass decided the shell stays where it is; false while a move is coming. */
    val settled: StateFlow<Boolean> = settledFlow

    /** Called by the placement after each pass: [stays] is true when it moves nothing. */
    fun settle(stays: Boolean) {
        settledFlow.value = stays
    }

    @Volatile
    private var rememberedInMemory: Int? = null

    /** The shell is in front on [displayId]: kept for the next process. A preferences write: off the main thread. */
    fun remember(context: Context, displayId: Int) {
        if (rememberedInMemory == displayId) return
        rememberedInMemory = displayId
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_DISPLAY, displayId).apply()
    }

    /** The display the shell was last in front on, or null before it ever was. */
    fun remembered(context: Context): Int? =
        rememberedInMemory ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .takeIf { it.contains(KEY_DISPLAY) }?.getInt(KEY_DISPLAY, 0)?.also { rememberedInMemory = it }

    /**
     * The display a Home press starts the shell on, or null for the press's own display: where the shell is now
     * ([liveShell], this process), else where it last was ([remembered]), when that display is connected ([present])
     * and is not [pressedOn], which a plain start reaches anyway. Pure.
     */
    fun launchDisplay(liveShell: Int?, remembered: Int?, present: Set<Int>, pressedOn: Int): Int? =
        (liveShell ?: remembered)?.takeIf { it != pressedOn && it in present }
}
