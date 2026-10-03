package dev.droidtop.library

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Windows environment's setup, as one state and one way to start it
 * (Droidtop/tracker#299). Settings' "Set up Windows games" row, A on a
 * Windows game that is not ready, the game menu's setup row and the game
 * page's primary button all start [provision] and all read [State], so
 * none of them can disagree about whether it is installing, finished or
 * failed, and the answer outlives the screen that asked.
 *
 * Whether the environment exists is [PcGameRuntime.isProvisioned], which
 * launch reads too; this adds what that boolean cannot say: that an install
 * is running and how far, and why the last one failed.
 */
object WindowsSetup {

    sealed interface State {
        data object NotSetUp : State
        data class Installing(val percent: Int?, val line: String) : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    /** What happened in this process: an install under way, or the one that just failed. Null when neither. */
    private val liveState = MutableStateFlow<State?>(null)
    val live: StateFlow<State?> get() = liveState

    /** One install at a time: a second ask waits and then finds it done (provisioning is idempotent). */
    private val running = Mutex()

    private const val PREFS = "windows_setup"
    private const val KEY_FAILURE = "last_failure"

    /**
     * The state to show, from the three facts: whether the environment
     * exists, the install under way (if any) and the last failure (if any,
     * cleared by the next attempt or a success). Pure, for the tests.
     */
    fun resolve(provisioned: Boolean, installing: State.Installing?, failure: String?): State = when {
        installing != null -> installing
        failure != null -> State.Failed(failure)
        provisioned -> State.Ready
        else -> State.NotSetUp
    }

    /** The label a row's value column or a button's detail line carries. Pure. */
    fun label(state: State): String = when (state) {
        State.NotSetUp -> "Not set up"
        is State.Installing -> state.percent?.let { "Installing $it%" } ?: "Installing"
        State.Ready -> "Ready"
        is State.Failed -> "Failed: ${state.reason}"
    }

    /** The percentage in a progress line ("Installing Windows system files... 9%"), or null. Pure. */
    fun percentIn(line: String): Int? =
        Regex("""(\d{1,3})\s*%""").find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

    /** The current state; reads a preference, so call it off the main thread. */
    fun current(context: Context): State {
        val installing = liveState.value as? State.Installing
        val failure = (liveState.value as? State.Failed)?.reason ?: lastFailure(context)
        return resolve(PcGameRuntimeRegistry.runtime?.isProvisioned == true, installing, failure)
    }

    /** The failure of the last attempt, kept across restarts until the next attempt starts. */
    fun lastFailure(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FAILURE, null)

    private fun saveFailure(context: Context, reason: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (reason == null) remove(KEY_FAILURE) else putString(KEY_FAILURE, reason)
        }.apply()
    }

    /**
     * Creates or repairs the environment over the games folders in force
     * ([GamesRoots.current]), reporting each progress line through
     * [onStatus] and into [live]. The caller has already asked (Settings'
     * confirmation, the launch path's offer); this never asks.
     */
    suspend fun provision(context: Context, onStatus: (String) -> Unit = {}): PcProvisionResult {
        val runtime = PcGameRuntimeRegistry.runtime
            ?: return PcProvisionResult(false, "Windows support isn't loaded in this build")
        return running.withLock {
            liveState.value = State.Installing(null, "Starting")
            saveFailure(context, null)
            val result = try {
                runtime.provision(GamesRoots.current(context)) { line ->
                    liveState.value = State.Installing(percentIn(line), line)
                    onStatus(line)
                }
            } catch (cancelled: CancellationException) {
                liveState.value = null
                throw cancelled
            } catch (t: Throwable) {
                PcProvisionResult(false, t.message ?: t.javaClass.simpleName)
            }
            if (result.succeeded) {
                liveState.value = null
            } else {
                liveState.value = State.Failed(result.detail)
                saveFailure(context, result.detail)
            }
            result
        }
    }
}
