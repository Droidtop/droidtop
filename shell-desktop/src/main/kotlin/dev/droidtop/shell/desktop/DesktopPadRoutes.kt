package dev.droidtop.shell.desktop

import android.view.InputDevice
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The pad buttons that reach droidtop's own chrome while Desktop is showing (docs/SPEC.md 2b "Desktop
 * chrome with a pad", Droidtop/tracker#350).
 *
 * Desktop hands the pad to the container (SPEC 6b: the right stick is the pointer, the stick clicks are
 * the mouse buttons) and the activity's input gate steps aside, so the taskbar, Start menu and tray had no
 * button of their own. Four buttons are droidtop's, three of them the ones Gaming gives its menus: Start opens the
 * Start menu (Gaming's left menu), R2 the Quick Menu (Gaming's R2; Select stays the container's), L1 the list of windows, R1 the search. They are claimed only from a gamepad. D-pad and face buttons are left alone, as before. The activity [claim]s the key before any view sees it; the shell collects [requests].
 *
 * A keyboard's keys are the container's, but for four shortcuts every desktop OS has (docs/SPEC.md 6b "Where
 * Meta goes", Droidtop/tracker#697), read by [KeyShortcuts]: a tap of Super alone opens Start, Super+S the
 * search, Alt+Tab the list of windows and Ctrl+Esc Start.
 */
object DesktopPadRoutes {
    enum class Route { START_MENU, QUICK_MENU, WINDOWS, SEARCH }

    private val routes = MutableSharedFlow<Route>(extraBufferCapacity = 4)

    /** One value per press, for the Desktop shell if it is showing (nobody collecting means the press is dropped). */
    val requests: SharedFlow<Route> = routes.asSharedFlow()

    /** Which route a pad key code is, or null for one that stays with the container. Pure. */
    internal fun routeFor(keyCode: Int): Route? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_START -> Route.START_MENU
        KeyEvent.KEYCODE_BUTTON_R2 -> Route.QUICK_MENU
        KeyEvent.KEYCODE_BUTTON_L1 -> Route.WINDOWS
        KeyEvent.KEYCODE_BUTTON_R1 -> Route.SEARCH
        else -> null
    }

    /**
     * Takes [event] when it is one of those buttons on a gamepad: the press raises its route once (a held
     * button does not repeat it) and the whole press, release included, is consumed so nothing else
     * sees half of it. True when taken. With no live session ([sessionLive] false) there is no container to
     * give a keyboard's keys to, so the same buttons are taken from any source (rig 2026-10-15: on a Desktop
     * still to be set up, Start and R2 sent as key events did nothing while touch worked).
     */
    fun claim(event: KeyEvent, sessionLive: Boolean = true): Boolean {
        val gamepad = event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!gamepad) {
            val (take, route) = keyboard.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, event.metaState)
            route?.let { routes.tryEmit(it) }
            if (take) return true
            if (sessionLive) return false
        }
        val route = routeFor(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) routes.tryEmit(route)
        return true
    }

    private val keyboard = KeyShortcuts()

    /**
     * The keyboard's way to droidtop's chrome (Droidtop/tracker#697). Pure: [onKey] says whether droidtop takes the
     * key and which route, if any, it raises.
     * - A tap of Super, pressed and let go with no other key or modifier, raises Start. Super itself always goes on
     *   to the container (it is sway's Mod4), and sway does nothing with Super alone.
     * - Super+S raises the search, Alt+Tab the windows, Ctrl+Esc Start. droidtop takes the S, Tab or Esc, its press
     *   and its release, so the container never sees half of it; the modifier went to the container already, alone,
     *   which does nothing there. Super+S takes the place of sway's stock `$mod+s` (stacking layout), which the
     *   floating desktop (#686) has little use for.
     */
    internal class KeyShortcuts {
        private var superAlone = false
        private var taken: Int? = null

        fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, metaState: Int): Pair<Boolean, Route?> {
            if (keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT) {
                if (down) {
                    if (repeatCount == 0) superAlone = metaState and OTHER_MODIFIERS == 0
                    return false to null
                }
                val route = if (superAlone) Route.START_MENU else null
                superAlone = false
                return false to route
            }
            if (!down) {
                if (taken == keyCode) {
                    taken = null
                    return true to null
                }
                return false to null
            }
            superAlone = false
            val route = when {
                keyCode == KeyEvent.KEYCODE_S && metaState and KeyEvent.META_META_ON != 0 -> Route.SEARCH
                keyCode == KeyEvent.KEYCODE_TAB && metaState and KeyEvent.META_ALT_ON != 0 -> Route.WINDOWS
                keyCode == KeyEvent.KEYCODE_ESCAPE && metaState and KeyEvent.META_CTRL_ON != 0 -> Route.START_MENU
                else -> return false to null
            }
            taken = keyCode
            return true to if (repeatCount == 0) route else null
        }

        private companion object {
            const val OTHER_MODIFIERS = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON
        }
    }
}
