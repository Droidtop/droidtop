package dev.droidtop.runtime

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The filtering Wayland sockets of containers whose clipboard is off (docs/SPEC.md 3d "Clipboard per container",
 * Droidtop/tracker#787 slice 2), one per container, started the first time that container's programs need one and
 * kept while droidtop runs: a container's processes come and go, its socket stays where they were told to look.
 *
 * Each backend binds [socketFor] over the compositor's socket name in that container's view of
 * [ContainerLayout.SOCKET_DIR], so `WAYLAND_DISPLAY` is unchanged and the real socket cannot be reached past it, and
 * binds [blank] over sway's IPC socket, whose `exec` command would otherwise run a program in the primary, where the
 * clipboard is.
 */
object ClipboardFilters {
    private val proxies = ConcurrentHashMap<String, WaylandFilterProxy>()

    /**
     * The filtering socket for [containerId], under [stateDir] (droidtop's own storage, never the shared socket folder),
     * reaching the compositor's current socket in [hostSocketDir]. Starts it when it is not running yet.
     */
    fun socketFor(containerId: String, stateDir: File, hostSocketDir: File): File {
        val socket = File(File(stateDir, "clipboard-filter"), "$containerId.sock")
        proxies.computeIfAbsent(containerId) {
            WaylandFilterProxy(socket) { ContainerLayout.findWaylandSocket(hostSocketDir) }
        }
        return socket
    }

    /** An empty file to bind over a socket a container whose clipboard is off must not reach. */
    fun blank(stateDir: File): File =
        File(File(stateDir, "clipboard-filter"), "blank").apply {
            parentFile?.mkdirs()
            if (!isFile) writeText("")
        }

    /** Stops [containerId]'s socket: its clipboard is on again, or it was deleted. */
    fun stop(containerId: String) {
        proxies.remove(containerId)?.close()
    }
}
