package dev.droidtop.library.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The in-place hints (docs/DESIGN-LANGUAGE.md, "Guidance"; Droidtop/tracker#829): one short line, shown where the thing
 * is done, until the person has done it or dismissed it. The owner, 2026-10-30: "minimally intrusive guidance telling
 * users how to do certain things". Whether a hint is done is kept in the one "seen" store that update onboarding's
 * steps use ([FeatureSteps]), under the hint's own id, so there is one record of what droidtop has already taught.
 * Hints are not registered steps, so onboarding's end (which marks every step seen) leaves them to show.
 */
object Hints {
    /** "Press and hold Home to switch modes", on Mobile's home screen: done when the mode switcher opens. */
    const val HOLD_HOME = "hint_hold_home"

    private val changed = MutableStateFlow(0)

    /** Bumped whenever a hint is done, so a hint on screen goes the moment it is. */
    val changes: StateFlow<Int> get() = changed

    /** Whether [id] has been done or dismissed. Reads preferences: off the main thread. */
    fun done(context: Context, id: String): Boolean = id in FeatureSteps.seen(context)

    /** [id] is done (the person did the thing, or dismissed the hint). Writes preferences: off the main thread. */
    fun markDone(context: Context, id: String) {
        FeatureSteps.markSeen(context, listOf(id))
        changed.value++
    }
}
