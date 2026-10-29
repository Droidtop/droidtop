package dev.droidtop.hostbridge

import android.util.Log
import dev.droidtop.runtime.ContainerLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The taskbar's window actions for the session's compositor: raise, minimize and restore, one place for both
 * compositors droidtop starts (Droidtop/tracker#145, #391).
 *
 * labwc implements wlr-foreign-toplevel-management's set_minimized (its wlr-foreign.c
 * handle_request_minimize), so there minimize and restore are that request. sway ignores it, so under sway
 * minimize is IPC's `move scratchpad` ([SwayIpc]) and restore is `scratchpad show`, followed by
 * `floating disable` for a window that was tiled, so it comes back into the layout rather than floating
 * (sway's `floating disable` also takes it out of the scratchpad, container_set_floating). The protocol has
 * no request that names a sway container, so the window is found in sway's tree by the app id and title the
 * protocol reported; with two windows alike, the window is raised first and the focused one is taken.
 *
 * sway reports a scratchpad window to the protocol only as not activated, so the windows this minimized
 * are kept in [hidden], which the taskbar reads as minimized; a window that comes back to the front by any
 * route (sway's own scratchpad key, a click in the desktop) leaves it ([forget]).
 */
class DesktopWindows(
    private val bridge: HostBridge,
    compositorCommand: String,
    private val hostSocketDir: File,
) {
    /** The program, not the command: the Waybar panel starts sway with droidtop's config (sway -c ...). */
    private val sway = compositorCommand.substringBefore(' ') == "sway"

    private val _hidden = MutableStateFlow<Map<Long, HiddenWindow>>(emptyMap())

    /** The toplevels this moved to sway's scratchpad, by toplevel id. Always empty under labwc. */
    val hidden: StateFlow<Map<Long, HiddenWindow>> = _hidden.asStateFlow()

    /** Whether [toplevel] is minimized, given the current [hidden]. */
    fun isMinimized(toplevel: Toplevel, hidden: Map<Long, HiddenWindow>): Boolean = toplevel.minimized || toplevel.id in hidden

    /**
     * Drops what [hidden] holds for windows that are gone or in front again; call it with every new window
     * list. A window list that still shows the window activated just after it was put away is the compositor's
     * report from before the move, so a fresh entry outlives it for [SETTLE_NS].
     */
    fun forget(toplevels: List<Toplevel>) {
        val now = System.nanoTime()
        val byId = toplevels.associateBy { it.id }
        _hidden.update { current ->
            current.filter { (id, entry) ->
                val toplevel = byId[id]
                toplevel != null && (!toplevel.activated || now - entry.at < SETTLE_NS)
            }
        }
    }

    /** Puts [toplevel] away. False when the compositor could not be asked. */
    suspend fun minimize(toplevel: Toplevel): Boolean {
        if (!sway) return bridge.setToplevelMinimized(toplevel.id, true)
        return withContext(Dispatchers.IO) {
            runCatching {
                val ipc = ipc() ?: return@runCatching false
                val window = findWindow(ipc, toplevel) ?: return@runCatching false
                ipc.run("[con_id=${window.conId}] move scratchpad").also { moved ->
                    if (moved) _hidden.update { it + (toplevel.id to HiddenWindow(window, System.nanoTime())) }
                }
            }.onFailure { Log.w(TAG, "sway minimize failed", it) }.getOrDefault(false)
        }
    }

    /** Brings [toplevel] to the front, restoring it first when it is minimized. */
    suspend fun raise(toplevel: Toplevel) {
        val window = _hidden.value[toplevel.id]?.window
        if (window != null) {
            val shown = withContext(Dispatchers.IO) {
                runCatching {
                    val then = if (window.floating) "" else ", floating disable"
                    ipc()?.run("[con_id=${window.conId}] scratchpad show$then") == true
                }.onFailure { Log.w(TAG, "sway restore failed", it) }.getOrDefault(false)
            }
            _hidden.update { it - toplevel.id }
            // A foreign-toplevel activate also shows a scratchpad window under sway
            // (view.c handle_foreign_activate_request), so it is the fallback.
            if (shown) return
        } else if (toplevel.minimized) {
            bridge.setToplevelMinimized(toplevel.id, false)
        }
        bridge.activateToplevel(toplevel.id)
    }

    /**
     * Switches to the next or previous workspace on the focused output (sway's `workspace next_on_output`), for the
     * trackpad's three-finger swipe (Droidtop/tracker#426). False under labwc, which takes no command from outside,
     * or when sway could not be asked.
     */
    suspend fun switchWorkspace(next: Boolean): Boolean {
        if (!sway) return false
        return withContext(Dispatchers.IO) {
            runCatching { ipc()?.run(if (next) "workspace next_on_output" else "workspace prev_on_output") == true }
                .onFailure { Log.w(TAG, "sway workspace switch failed", it) }
                .getOrDefault(false)
        }
    }

    private fun ipc(): SwayIpc? = ContainerLayout.findSwayIpcSocket(hostSocketDir)?.let { SwayIpc(it.absolutePath) }

    /** [toplevel]'s container in sway's tree: the one match, or, among several alike, the focused one after raising it. */
    private suspend fun findWindow(ipc: SwayIpc, toplevel: Toplevel): SwayWindow? {
        val matches = SwayIpc.windows(ipc.tree(), toplevel.appId, toplevel.title)
        if (matches.size == 1) return matches[0]
        if (matches.isEmpty()) return null
        if (!toplevel.activated) bridge.activateToplevel(toplevel.id)
        repeat(FOCUS_POLLS) {
            SwayIpc.windows(ipc.tree(), toplevel.appId, toplevel.title).firstOrNull { it.focused }?.let { return it }
            delay(FOCUS_POLL_MS)
        }
        return null
    }

    private companion object {
        const val TAG = "DesktopWindows"
        const val FOCUS_POLLS = 10
        const val FOCUS_POLL_MS = 50L
        const val SETTLE_NS = 1_000_000_000L
    }
}

/** A window [DesktopWindows] put in sway's scratchpad, and when (System.nanoTime). */
data class HiddenWindow(val window: SwayWindow, val at: Long)
