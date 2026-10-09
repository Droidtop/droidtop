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
 * button of their own. Three buttons are droidtop's, the ones Gaming gives its menus: Start opens the
 * Start menu (Gaming's left menu), Select or R2 the Quick Menu (Gaming's R2; a pad whose triggers send no
 * key still has Select), L1 the list of windows. They are claimed only from a gamepad, never from a
 * keyboard: a keyboard's keys are the container's. D-pad, face buttons and the other shoulder are left
 * alone, as before. The activity [claim]s the key before any view sees it; the shell collects [requests].
 */
object DesktopPadRoutes {
    enum class Route { START_MENU, QUICK_MENU, WINDOWS }

    private val routes = MutableSharedFlow<Route>(extraBufferCapacity = 4)

    /** One value per press, for the Desktop shell if it is showing (nobody collecting means the press is dropped). */
    val requests: SharedFlow<Route> = routes.asSharedFlow()

    /** Which route a pad key code is, or null for one that stays with the container. Pure. */
    internal fun routeFor(keyCode: Int): Route? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_START -> Route.START_MENU
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_R2 -> Route.QUICK_MENU
        KeyEvent.KEYCODE_BUTTON_L1 -> Route.WINDOWS
        else -> null
    }

    /**
     * Takes [event] when it is one of those buttons on a gamepad: the press raises its route once (a held
     * button does not repeat it) and the whole press, release included, is consumed so nothing else
     * sees half of it. True when taken.
     */
    fun claim(event: KeyEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return false
        val route = routeFor(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) routes.tryEmit(route)
        return true
    }
}
