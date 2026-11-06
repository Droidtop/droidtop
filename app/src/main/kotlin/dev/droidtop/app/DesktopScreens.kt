package dev.droidtop.app

import android.app.Application
import android.os.Build
import android.util.Log
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.runtime.DesktopExtension
import dev.droidtop.runtime.DisplayArrangement
import dev.droidtop.runtime.DisplayOutput
import dev.droidtop.runtime.DisplayOutputRepository
import dev.droidtop.runtime.ScreenNaming
import dev.droidtop.runtime.ScreenRoles
import dev.droidtop.runtime.util.AppScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts the desktop on the screens the person said it extends to (docs/SPEC.md 2b, "The desktop on a second screen",
 * Droidtop/tracker#908 step D5). While the desktop is up in Desktop mode, every screen other than its own whose
 * Desktop answer is Extend ([ScreenRoles.desktopExtends]) gets it ([DesktopSecondScreen.show]); a screen whose answer
 * changes, the desktop stopping, or a switch to another mode takes it off again ([DesktopSecondScreen.hide]). Each
 * pass also asks about a screen with no answer yet ([ScreenRoleAsk.check], once per screen).
 *
 * Which displays have it is published as [DesktopExtension] before the desktop opens on one and only after it closed
 * there, so the second-screen orchestration never puts the companion or the idle cover over it. Preferences and the
 * display list are read on the IO dispatcher; the windows are opened and closed on the main thread. Main process only.
 */
internal object DesktopScreens {
    private const val TAG = "droidtop.DesktopScreens"

    /** The displays this has opened the desktop on. Main thread. */
    private val opened = mutableSetOf<Int>()

    fun install(application: Application) {
        val main = Build.VERSION.SDK_INT < 28 || Application.getProcessName() == application.packageName
        if (!main) return
        val displays = DisplayOutputRepository(application)
        AppScopes.main.launch {
            combine(
                DesktopSessionService.state
                    .map { (it as? DesktopSessionState.Connected)?.primaryOutput?.androidDisplayId }
                    .distinctUntilChanged(),
                Modes.lastModeFlow,
                DisplayArrangement.refresh,
                displays.observe(),
            ) { desktopId, mode, _, outputs -> desktopId?.takeIf { mode == Mode.DESKTOP.id } to outputs }
                .collectLatest { (desktopId, outputs) -> apply(application, desktopId, outputs) }
        }
    }

    /** The display the desktop runs on while it is up in Desktop mode, else null. Any thread. */
    fun desktopDisplayId(): Int? {
        if (Modes.lastModeFlow.value != Mode.DESKTOP.id) return null
        return (DesktopSessionService.state.value as? DesktopSessionState.Connected)?.primaryOutput?.androidDisplayId
    }

    private suspend fun apply(app: Application, desktopId: Int?, outputs: List<DisplayOutput>) {
        val wanted = if (desktopId == null) {
            emptySet()
        } else {
            withContext(Dispatchers.IO) {
                ScreenRoles.desktopExtends(outputs.map(ScreenNaming::facts), desktopId, ScreenRoles.desktopSaved(app))
            }
        }
        // Off first, then published: the companion may come back to a screen only once the desktop has left it.
        (opened - wanted).forEach { displayId ->
            DesktopSecondScreen.hide(displayId)
            opened -= displayId
        }
        // Published before the desktop opens anywhere new, so no pass puts the companion over it.
        DesktopExtension.set(wanted)
        (wanted - opened).forEach { displayId ->
            val display = outputs.firstOrNull { it.androidDisplayId == displayId } ?: return@forEach
            // Once per display while it stays wanted: show again would size the output to the whole screen, over the
            // size its window asked for beside its bar (DesktopSecondScreen.surfaceSized).
            opened += displayId
            if (!DesktopSecondScreen.show(app, display)) Log.w(TAG, "The desktop could not be opened on display $displayId")
        }
        if (desktopId != null) ScreenRoleAsk.check()
    }
}
