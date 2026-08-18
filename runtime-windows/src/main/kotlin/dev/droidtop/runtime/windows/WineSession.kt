package dev.droidtop.runtime.windows

import dev.droidtop.runtime.Container

/**
 * A Wine prefix, ported from vendor/gamenative's com.winlator tree. Runs as
 * an ordinary Linux process inside a [Container] (primary or sibling) —
 * exactly like running Wine on any real Linux desktop — using Wine's native
 * Wayland driver pointed at that container's Wayland socket.
 *
 * Deliberately NOT ported from upstream: Winlator's own Android SurfaceView
 * XServer. That entire rendering path is replaced by "just be a Wayland
 * client," which is also why this class has no display/surface code at all
 * — display is host-bridge's problem, not this module's.
 *
 * Kept from upstream: the Wine build + Box64 (v0.3.6 baseline) + DXVK/VKD3D
 * translation stack, and the ImageFS container layout for tracking per-game
 * Wine prefix state (drive_c, registry, installed components).
 */
class WineSession(
    val container: Container,
    val prefixPath: String,
) {
    /** True once a Wayland driver build of Wine is confirmed viable; see docs/SPEC.md open risks. */
    var usesNativeWaylandDriver: Boolean = false
        internal set

    fun launch(executablePath: String, args: List<String> = emptyList()) {
        TODO("Port Wine/Box64 process launch from vendor/gamenative, targeting the container's WAYLAND_DISPLAY")
    }
}
