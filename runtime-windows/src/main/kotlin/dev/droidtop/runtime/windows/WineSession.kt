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
     * `box64 wine <executable>` launch via [ContainerRuntime.exec], with
     * the real gamenative guest environment ([WineLaunchEnvironment] — the
     * transcribed base + Box64 env sets, adapted to droidtop's Wayland/
     * container model; see that object's own doc comment for exactly what
     * was kept vs. adapted). Box64 wraps wine exactly like upstream's own
     * `"box64 " + guestExecutable`; [useBox64] = false runs a native-arm64
     * wine build (or FEXCore-managed setup) directly instead — §5a's
     * backend choice, decided by the caller, not here. Still honestly NOT
     * ported: DXVK/VKD3D per-game component state (ImageFS install
     * tracking) and Box64 preset management (Box86_64PresetManager) —
     * real, separate porting steps.
     */
    suspend fun launch(
        executablePath: String,
        args: List<String> = emptyList(),
        useBox64: Boolean = true,
        extraEnv: Map<String, String> = emptyMap(),
    ): ContainerExecResult {
        val wineInvocation = listOf("wine", executablePath) + args
        return runtime.exec(
            container = container,
            command = if (useBox64) listOf("box64") + wineInvocation else wineInvocation,
            env = WineLaunchEnvironment.build(prefixPath = prefixPath, extra = extraEnv),
        )
    }
}
