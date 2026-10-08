package dev.droidtop.runtime.keyboard

import dev.droidtop.runtime.tasks.Fidelity
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.tasks.RunningSnapshot

/**
 * Which app gets the system's focus back when a touch-only companion surface became the top activity while the
 * user was in an app on the other screen (docs/SPEC.md 4c, "Typing on the add-on display", Droidtop/tracker#314).
 * Android sends every key, and binds the keyboard, to the top-focused display; a companion that took that role
 * leaves the app on the other screen with no keys at all.
 */
object FocusReturn {
    /**
     * The app visible on another display than [fromDisplayId], read from the system's own task list. Only an EXACT
     * list says what is visible; the launch ledger only knows what droidtop opened, not whether it still shows, so
     * with it nothing is moved. droidtop's own tasks are the shell's job ([ownPackage]).
     */
    fun appToRefocus(snapshot: RunningSnapshot, fromDisplayId: Int?, ownPackage: String): RunningApp? {
        if (snapshot.fidelity != Fidelity.EXACT) return null
        return snapshot.apps.firstOrNull { it.visible && it.displayId != fromDisplayId && it.packageName != ownPackage }
    }
}
