package dev.droidtop.runtime.windows

import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerExecResult
import dev.droidtop.runtime.ContainerRuntime

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
 * Wine prefix state (drive_c, registry, installed components). Also
 * available (§3c/§5a): FEXCore as an alternative CPU-translation backend to
 * Box64, and preferring a native Linux depot over Wine entirely when one
 * exists — see [dev.droidtop.runtime.NativeLinuxGameSession] for that path.
 */
class WineSession(
    val container: Container,
    val runtime: ContainerRuntime,
    val prefixPath: String,
) {
    /** True once a Wayland driver build of Wine is confirmed viable; see docs/SPEC.md open risks. */
    var usesNativeWaylandDriver: Boolean = false
        internal set

    /**
     * Basic `wine <executable>` launch via [ContainerRuntime.exec] — the
     * exec-into-container plumbing this depended on ([ContainerRuntime.exec]
     * itself) is real now, but this is still only the bare invocation, NOT
     * a full port of gamenative's launch pipeline: no Box64/FEXCore wrapper
     * selection (§5a), no DXVK/VKD3D env vars, no ImageFS-tracked component
     * state. Those are real, separate porting work, not done here.
     */
    suspend fun launch(executablePath: String, args: List<String> = emptyList()): ContainerExecResult =
        runtime.exec(
            container = container,
            command = listOf("wine", executablePath) + args,
            env = mapOf("WINEPREFIX" to prefixPath, "WAYLAND_DISPLAY" to "wayland-0"),
        )
}
