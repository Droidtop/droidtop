package dev.droidtop.hostbridge

import android.util.Log
import dev.droidtop.runtime.ContainerLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicReference

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
 * protocol reported, among the windows on the output it reported first (Droidtop/tracker#908, step D4); with two
 * windows alike there too, the window is raised first and the focused one is taken.
 *
 * sway reports a scratchpad window to the protocol only as not activated, so the windows this minimized
 * are kept in [hidden], which the taskbar reads as minimized; a window that comes back to the front by any
 * route (sway's own scratchpad key, a click in the desktop) leaves it ([forget]).
 *
 * The title-bar buttons droidtop draws over sway's windows (docs/SPEC.md 2b "Window controls", Droidtop/tracker#910)
 * take the same actions by the window's container id, which sway's tree gives them ([frames], [close], [minimize],
 * [toggleMaximize]); the bar, which knows a window only by the protocol, still finds it with [findWindow].
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

    private val _placed = MutableStateFlow<Map<Long, SwayWindow>>(emptyMap())

    /**
     * The windows this maximized or snapped, by toplevel id, each with where it was before (Droidtop/tracker#686),
     * so the bar offers Restore size. Always empty under labwc, whose windows have their own title-bar buttons.
     */
    val placed: StateFlow<Map<Long, SwayWindow>> = _placed.asStateFlow()

    /** Whether the bar can place windows: sway only, as labwc draws its own maximize and close buttons. */
    val canPlace: Boolean get() = sway

    /**
     * Whether a window can be moved to another screen: sway only, the one compositor droidtop gives a second output
     * (Droidtop/tracker#908, step D2). The action is offered only while a second output exists ([WindowOutputs]).
     */
    val canMove: Boolean get() = sway

    /**
     * Moves [toplevel] to the compositor output [outputName] (Droidtop/tracker#908, step D4): sway's
     * `move container to output`, which puts it on that output's visible workspace, tiled or floating as it was.
     * Nothing about the focus is asked: it stays where sway leaves it. False under labwc, for a name that is not an
     * output's, or when sway could not be asked or found no such window.
     */
    suspend fun moveToOutput(toplevel: Toplevel, outputName: String): Boolean {
        if (!sway || !DesktopOutputs.isOutputName(outputName)) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val ipc = ipc() ?: return@runCatching false
                val window = findWindow(ipc, toplevel) ?: return@runCatching false
                ipc.run(moveCommand(window.conId, outputName))
            }.onFailure { Log.w(TAG, "sway move to $outputName failed", it) }.getOrDefault(false)
        }
    }

    /**
     * Maximizes, snaps or restores [toplevel] (docs/SPEC.md 2b "Window controls", Droidtop/tracker#686): what a
     * title bar's buttons and an edge drag do on DeX or Android's desktop windowing, which sway's title bars do not
     * have. The window floats for it, so it can be put anywhere; [Placement.RESTORE] puts it back where it was,
     * tiled again when it was tiled. False when sway could not be asked.
     */
    suspend fun place(toplevel: Toplevel, placement: Placement): Boolean {
        if (!sway) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val ipc = ipc() ?: return@runCatching false
                val window = findWindow(ipc, toplevel) ?: return@runCatching false
                placeContainer(ipc, window, toplevel.id, placement)
            }.onFailure { Log.w(TAG, "sway $placement failed", it) }.getOrDefault(false).also { refreshFrames() }
        }
    }

    /**
     * Puts the container [window] at [placement] and keeps where it was under [key], the toplevel the bar lists it as;
     * with no key the window is placed and nothing is kept, so it has no Restore size. Blocking: on [Dispatchers.IO].
     */
    private fun placeContainer(ipc: SwayIpc, window: SwayWindow, key: Long?, placement: Placement): Boolean {
        val before = key?.let { _placed.value[it] }
        if (placement == Placement.RESTORE && before == null) return false
        return ipc.run(placeCommand(window.conId, placement, before)).also { done ->
            if (!done || key == null) return@also
            _placed.update {
                when (placement) {
                    Placement.RESTORE -> it - key
                    // The first placement remembers where the window was; snapping it again keeps that.
                    else -> if (key in it) it else it + (key to window)
                }
            }
        }
    }

    /** Moves the container [window] to the scratchpad and, under [key], keeps it as minimized. Blocking. */
    private fun minimizeContainer(ipc: SwayIpc, window: SwayWindow, key: Long?): Boolean =
        ipc.run("[con_id=${window.conId}] move scratchpad").also { moved ->
            if (moved && key != null) _hidden.update { it + (key to HiddenWindow(window, System.nanoTime())) }
        }

    private val frameRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Where sway shows each window, read from its tree off the main thread while anything collects it (the desktop's
     * surfaces, for their title-bar buttons and resize bands, Droidtop/tracker#910). Read again on every window,
     * workspace and output event sway sends, on [refreshFrames], and every [FRAMES_POLL_MS] for what sway sends no
     * event for (a window dragged or resized by the pointer, or resizing itself). Always [SwayFrames.NONE] under
     * labwc, which draws its own title-bar buttons.
     */
    val frames: StateFlow<SwayFrames> by lazy {
        if (!sway) {
            MutableStateFlow(SwayFrames.NONE).asStateFlow()
        } else {
            watchFrames().flowOn(Dispatchers.IO).distinctUntilChanged()
                .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.WhileSubscribed(FRAMES_KEEP_MS), SwayFrames.NONE)
        }
    }

    /** Has [frames] read sway's tree again soon: a press on the desktop ended, or droidtop moved a window itself. */
    fun refreshFrames() {
        frameRequests.tryEmit(Unit)
    }

    private fun watchFrames(): Flow<SwayFrames> = channelFlow {
        val wake = Channel<Unit>(Channel.CONFLATED)
        launch { frameRequests.collect { wake.trySend(Unit) } }
        // next() blocks on the socket, which a cancellation does not interrupt: closing the socket ends the wait.
        val open = AtomicReference<SwayEvents?>(null)
        launch {
            try {
                awaitCancellation()
            } finally {
                open.getAndSet(null)?.close()
            }
        }
        launch {
            while (isActive) {
                val events = runCatching { ipc()?.subscribe(FRAME_EVENTS) }
                    .onFailure { Log.w(TAG, "sway event subscription failed", it) }.getOrNull()
                if (events != null) {
                    open.set(events)
                    try {
                        while (isActive) {
                            events.next()
                            wake.trySend(Unit)
                        }
                    } catch (e: java.io.IOException) {
                        if (isActive) Log.i(TAG, "sway events ended: ${e.message}")
                    } finally {
                        open.compareAndSet(events, null)
                        events.close()
                    }
                }
                delay(FRAMES_RETRY_MS)
            }
        }
        while (isActive) {
            send(readFrames())
            withTimeoutOrNull(FRAMES_POLL_MS) { wake.receive() }
            // A change comes in bursts (a window opening is new, focus, title): read once they have settled.
            delay(FRAMES_SETTLE_MS)
            wake.tryReceive()
        }
    }

    private fun readFrames(): SwayFrames =
        runCatching { ipc()?.tree()?.let { SwayFrames.parse(it) } }
            .onFailure { Log.w(TAG, "sway tree read failed", it) }.getOrNull() ?: SwayFrames.NONE

    /** The toplevels the bar was last given ([forget]), to tell which one a container is. */
    @Volatile
    private var toplevels: List<Toplevel> = emptyList()

    /**
     * The key [DesktopWindows] keeps [window]'s state under: the toplevel already kept for its container, else the one
     * toplevel with its app id and title. Null when two windows alike leave it unknown: the action is taken all the
     * same, the bar just does not show it minimized or offer Restore size.
     */
    private fun keyOf(window: WindowFrame): Long? =
        _placed.value.entries.firstOrNull { it.value.conId == window.conId }?.key
            ?: toplevels.filter { it.appId == window.appId && it.title == window.title }.singleOrNull()?.id

    /** Whether the container [conId] was maximized or snapped by droidtop and can be restored. */
    fun isPlaced(conId: Long, placed: Map<Long, SwayWindow>): Boolean = placed.values.any { it.conId == conId }

    /** Closes the container [window] (its title bar's close), as the bar's Close does: sway asks the program to close. */
    suspend fun close(window: WindowFrame): Boolean = byContainer("close") { ipc -> ipc.run("[con_id=${window.conId}] kill") }

    /** Minimizes the container [window] (its title bar's minimise), as the bar's Minimize does. */
    suspend fun minimize(window: WindowFrame): Boolean = byContainer("minimize") { ipc -> minimizeContainer(ipc, window.window, keyOf(window)) }

    /** Maximizes the container [window], or restores it when droidtop maximized or snapped it (its title bar's button). */
    suspend fun toggleMaximize(window: WindowFrame): Boolean = byContainer("maximize") { ipc ->
        val key = keyOf(window)
        val placement = if (key != null && key in _placed.value) Placement.RESTORE else Placement.MAXIMIZE
        placeContainer(ipc, window.window, key, placement)
    }

    private suspend fun byContainer(what: String, action: (SwayIpc) -> Boolean): Boolean {
        if (!sway) return false
        return withContext(Dispatchers.IO) {
            runCatching { ipc()?.let(action) ?: false }
                .onFailure { Log.w(TAG, "sway $what failed", it) }.getOrDefault(false)
                .also { refreshFrames() }
        }
    }

    /** Whether [toplevel] is minimized, given the current [hidden]. */
    fun isMinimized(toplevel: Toplevel, hidden: Map<Long, HiddenWindow>): Boolean = toplevel.minimized || toplevel.id in hidden

    /**
     * Drops what [hidden] holds for windows that are gone or in front again; call it with every new window
     * list. A window list that still shows the window activated just after it was put away is the compositor's
     * report from before the move, so a fresh entry outlives it for [SETTLE_NS].
     */
    fun forget(toplevels: List<Toplevel>) {
        this.toplevels = toplevels
        val now = System.nanoTime()
        val byId = toplevels.associateBy { it.id }
        _hidden.update { current ->
            current.filter { (id, entry) ->
                val toplevel = byId[id]
                toplevel != null && (!toplevel.activated || now - entry.at < SETTLE_NS)
            }
        }
        _placed.update { current -> current.filterKeys { it in byId } }
    }

    /** Puts [toplevel] away. False when the compositor could not be asked. */
    suspend fun minimize(toplevel: Toplevel): Boolean {
        if (!sway) return bridge.setToplevelMinimized(toplevel.id, true)
        return withContext(Dispatchers.IO) {
            runCatching {
                val ipc = ipc() ?: return@runCatching false
                val window = findWindow(ipc, toplevel) ?: return@runCatching false
                minimizeContainer(ipc, window, toplevel.id)
            }.onFailure { Log.w(TAG, "sway minimize failed", it) }.getOrDefault(false).also { refreshFrames() }
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

    /**
     * [toplevel]'s container in sway's tree: the one match, the one match on the output the protocol says it is on, or,
     * among several alike, the focused one after raising it.
     */
    private suspend fun findWindow(ipc: SwayIpc, toplevel: Toplevel): SwayWindow? {
        val tree = ipc.tree()
        val matches = SwayIpc.windows(tree, toplevel.appId, toplevel.title)
        if (matches.size == 1) return matches[0]
        if (matches.size > 1 && toplevel.output.isNotEmpty()) {
            SwayIpc.windows(tree, toplevel.appId, toplevel.title, toplevel.output).singleOrNull()?.let { return it }
        }
        if (matches.isEmpty()) return null
        if (!toplevel.activated) bridge.activateToplevel(toplevel.id)
        repeat(FOCUS_POLLS) {
            SwayIpc.windows(ipc.tree(), toplevel.appId, toplevel.title).firstOrNull { it.focused }?.let { return it }
            delay(FOCUS_POLL_MS)
        }
        return null
    }

    /** Where the bar can put a window. */
    enum class Placement { MAXIMIZE, SNAP_LEFT, SNAP_RIGHT, RESTORE }

    internal companion object {
        const val TAG = "DesktopWindows"
        const val FOCUS_POLLS = 10
        const val FOCUS_POLL_MS = 50L
        const val SETTLE_NS = 1_000_000_000L

        /** The sway command that moves container [conId] to the output [outputName] (sway/commands/move.c). */
        fun moveCommand(conId: Long, outputName: String): String {
            require(DesktopOutputs.isOutputName(outputName)) { "not an output name: $outputName" }
            return "[con_id=$conId] move container to output $outputName"
        }

        /** What [frames] is read again for: a window, a workspace or an output changed. */
        val FRAME_EVENTS = listOf("window", "workspace", "output")

        /** How often [frames] reads the tree with no event, for what sway sends none for. */
        const val FRAMES_POLL_MS = 2_000L
        const val FRAMES_SETTLE_MS = 50L
        const val FRAMES_RETRY_MS = 2_000L

        /** How long [frames] keeps watching after its last collector goes: a rotation recreates the surface. */
        const val FRAMES_KEEP_MS = 5_000L

        /**
         * The sway command that puts container [conId] at [placement]. Sizes in `ppt` are of the workspace, the
         * desktop less Waybar (sway-ipc's `resize set` and `move position` on a floating container), so a
         * maximized window leaves the status strip showing. [before] is the window as it was before its first
         * placement, which [Placement.RESTORE] returns it to: back into the tiling when it was tiled, otherwise
         * its old size and absolute position.
         */
        fun placeCommand(conId: Long, placement: Placement, before: SwayWindow?): String {
            val target = "[con_id=$conId]"
            return when (placement) {
                Placement.MAXIMIZE -> "$target floating enable, resize set width 100 ppt height 100 ppt, move position 0 ppt 0 ppt"
                Placement.SNAP_LEFT -> "$target floating enable, resize set width 50 ppt height 100 ppt, move position 0 ppt 0 ppt"
                Placement.SNAP_RIGHT -> "$target floating enable, resize set width 50 ppt height 100 ppt, move position 50 ppt 0 ppt"
                Placement.RESTORE -> {
                    val rect = before?.rect
                    when {
                        before == null || !before.floating -> "$target floating disable"
                        rect == null -> "$target move position center"
                        else -> "$target resize set width ${rect.width} px height ${rect.height} px, " +
                            "move absolute position ${rect.x} px ${rect.y} px"
                    }
                }
            }
        }
    }
}

/** A window [DesktopWindows] put in sway's scratchpad, and when (System.nanoTime). */
data class HiddenWindow(val window: SwayWindow, val at: Long)
