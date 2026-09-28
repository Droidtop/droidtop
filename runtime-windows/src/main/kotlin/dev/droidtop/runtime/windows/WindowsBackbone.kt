package dev.droidtop.runtime.windows

import android.app.Application
import android.content.Context
import app.gamenative.PluviaApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The vendored gamenative backbone's process bootstrap, as something the
 * rest of droidtop can name. The vendored tree is compiled INTO this
 * module (`runtime-windows/build.gradle.kts` srcDirs), so `app.gamenative`
 * is this module's own compile classpath and nobody else's: `:app` depends
 * on `:runtime-windows`, not on gamenative, and mode gating in
 * `dev.droidtop.app.ModeStartup` reaches the bootstrap through here rather
 * than through a class it cannot see.
 *
 * What the bootstrap does: preferences, the download service, Steam
 * prerequisites, the container migration and the container-file preload,
 * telemetry setup, and a native library preload. It is the heaviest thing
 * droidtop starts, it reaches the network, and most of `PluviaApp.
 * bootstrap`'s own body runs on the calling thread rather than a
 * background one (Droidtop/tracker#41: a 59s single call, traced to
 * `PluviaApp.bootstrap` on the main thread by way of `ensureGamenative` /
 * `Modes.reload` / `OnboardingActivity.finishOnboarding`). `vendor/
 * gamenative` is upstream code we hook rather than rewrite, so the fix
 * lives here: this object is the one place that calls into it, and it
 * now always does so off the caller's thread, on its own background
 * scope, tracked by [state] so a caller that genuinely needs the result
 * (the PC launch path, the store and container-config screens) can
 * suspend on [awaitReady] instead of assuming the call already finished.
 *
 * [ensureStarted] itself never blocks and is safe to call repeatedly
 * (from every `ModeStartup.apply` pass, e.g. a tab switch that re-applies
 * the current mode set) -- the backing coroutine is launched at most
 * once, guarded by [state].
 */
object WindowsBackbone {

    enum class State { NOT_STARTED, STARTING, READY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State.NOT_STARTED)

    /** Current bootstrap progress, for a screen that wants to show it. */
    val state: StateFlow<State> = _state

    /**
     * Starts the backbone in the background if it is not already up or
     * starting, and returns immediately either way. Crash handling stays
     * droidtop's own (Bugsink, installed by `LauncherApplication`), hence
     * the explicit `installCrashHandler = false`.
     */
    @JvmStatic
    fun ensureStarted(context: Context) {
        val app = context.applicationContext as? Application ?: return
        // compareAndSet so two racing callers (mode-apply and a PC screen
        // opening at the same time) launch the coroutine exactly once.
        if (!_state.compareAndSet(State.NOT_STARTED, State.STARTING)) return
        scope.launch {
            try {
                PluviaApp.bootstrap(app, installCrashHandler = false)
            } finally {
                _state.value = State.READY
            }
        }
    }

    /**
     * Starts the backbone if needed and suspends until it is ready. For
     * the handful of screens that cannot show gamenative UI before the
     * bootstrap has actually run (the PC store, container config, Steam
     * sign-in, launching a Windows game): they call this from a
     * `LaunchedEffect`/coroutine and show a "Preparing Windows support…"
     * state while it is pending, rather than assuming `ensureStarted`
     * already finished the way the old synchronous call let them.
     */
    suspend fun awaitReady(context: Context) {
        ensureStarted(context)
        state.filter { it == State.READY }.first()
    }
}
