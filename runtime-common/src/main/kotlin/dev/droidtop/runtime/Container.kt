package dev.droidtop.runtime

import java.io.File

/**
 * A running Linux container, regardless of which backend created it
 * (runtime-linux-root's DroidSpaces fork, runtime-linux-noroot's proot
 * fallback, or a runtime-windows Wine prefix — Wine runs as an ordinary
 * process inside a container too, it doesn't get its own container type).
 */
data class Container(
    val id: String,
    val role: ContainerRole,
    val backend: ContainerBackend,
    val rootfsPath: String,
)

/**
 * Exactly one running [Container] on the device holds [ContainerRole.PRIMARY]
 * at a time: it's the one running the desktop compositor (vendor/sway,
 * headless-output build) that every other window ultimately composites into.
 * Everything else is a SIBLING that shares the primary's Wayland socket —
 * this mirrors distrobox's host-integration model, and Qubes' dom0/AppVM
 * split (the primary container plays dom0's GUI role; siblings are AppVMs).
 */
enum class ContainerRole { PRIMARY, SIBLING }

/**
 * Which backend is running a given container. Chosen automatically per
 * device based on root availability — callers of runtime-common should not
 * need to care which one they got.
 */
enum class ContainerBackend {
    /** Namespaces + cgroups, forked from vendor/droidspaces. Requires root. */
    DROIDSPACES,

    /** ptrace-based, no root required. Higher overhead, weaker isolation. */
    PROOT,
}

/** Result of [ContainerRuntime.exec] — deliberately not tied to any one backend's own process-result type. */
data class ContainerExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * One row of [ContainerRuntime.listContainers] — a known container (running
 * or not) plus the live state a management surface needs to render it
 * (docs/SPEC.md §3d's container manager is the real consumer).
 */
data class ContainerInfo(val container: Container, val running: Boolean)

/** Common lifecycle surface both container backends implement. */
interface ContainerRuntime {
    val backend: ContainerBackend

    /**
     * Every container this backend knows about on this device — running or
     * stopped — with live running state. "Knows about" means created by
     * droidtop through this runtime (each backend persists its own
     * per-container config; that persisted set IS the list), not a scan of
     * arbitrary processes.
     */
    suspend fun listContainers(): List<ContainerInfo>

    /**
     * [image] is caller-chosen — from the live-resolved catalog
     * ([ResolvedImage.toRootfsImage], see docs/SPEC.md §3a) or a hand-typed
     * custom OCI reference alike. [image] is expected to be a stock distro
     * image with no compositor preinstalled — [provisionCommand] (see
     * [CompositorProvisioning]) is what a backend runs, once, on the
     * container's first boot to actually install one, so the same "any OCI
     * image works" story (§3a) holds for the PRIMARY role too, not just
     * siblings. Null means "assume [image] already has a working
     * compositor + init" (e.g. a hand-typed custom reference) — this
     * interface doesn't validate either way, the caller is responsible for
     * picking a PRIMARY-appropriate entry and the matching command.
     */
    suspend fun createPrimary(image: RootfsImage, provisionCommand: String? = null): Container

    /** [image] is any SIBLING/BOTH-appropriate reference — no compositor needed. */
    suspend fun createSibling(image: RootfsImage): Container

    suspend fun start(container: Container)
    suspend fun stop(container: Container)
    suspend fun destroy(container: Container)

    /**
     * Runs [command] as a process inside an already-running [container] —
     * the primitive both `:runtime-windows`'s `WineSession.launch()` and a
     * native-Linux-depot launch (§5a) need: something that actually starts
     * a process *inside* the container, as opposed to the container
     * lifecycle operations above. [env] is merged into the process'
     * environment (e.g. `WAYLAND_DISPLAY`, `WINEPREFIX`).
     */
    suspend fun exec(container: Container, command: List<String>, env: Map<String, String> = emptyMap()): ContainerExecResult

    /**
     * Host-visible filesystem path to the primary container's Wayland
     * socket — what `:host-bridge`'s `HostBridge.connect()` needs. Only
     * meaningful after `createPrimary()`/`start()` on the PRIMARY
     * container; each backend owns the actual bind-mount/socket-sharing
     * mechanics (see e.g. DroidSpacesRuntime's own doc comment) and just
     * needs to expose where the result landed on the Android side.
     */
    fun primaryWaylandSocketPath(): String

    /**
     * Translates a host-visible path under the app's own private storage
     * (`Context.getFilesDir()` or a subtree of it — e.g. gamenative's
     * `com.winlator.container.Container.getRootDir()`, which is where a
     * per-game Wine prefix physically lives, entirely outside any Linux
     * container's own rootfs) into the equivalent path visible *inside* a
     * running container.
     *
     * Needed because `:runtime-windows`'s [dev.droidtop.runtime.windows.
     * WineSession] runs `wine` via [exec] — a process inside the container's
     * own mount namespace — but the Wine prefix it needs (`WINEPREFIX`) is
     * gamenative's own on-host storage, not anything already inside the
     * container's rootfs. Backends that isolate containers behind a real
     * mount namespace can't just hand that host path to `exec` unmodified;
     * they instead bind-mount the whole app-storage directory into every
     * container they create at a fixed in-container path (see
     * DroidSpacesRuntime's own doc comment) and this method does the prefix
     * substitution. [hostPath] must be under the app's private storage root
     * — passing anything else is a caller bug.
     */
    fun hostStorageToContainerPath(hostPath: File): String
}

/**
 * The live primary [Container] + the [ContainerRuntime] that owns it — what
 * a Wayland-client workload (`:runtime-windows`'s `WineSession`/
 * `PcGameProvider`, in particular) needs to actually launch a game alongside
 * the running desktop. Owned by `:app`'s `DesktopSessionService`; exposed as
 * this plain value type (rather than `runtime-windows` depending on `:app`'s
 * `DesktopSessionState` directly, which would be a circular module
 * dependency — `:app` depends on `:runtime-windows`, not the reverse).
 */
data class PrimaryContainerSession(val runtime: ContainerRuntime, val container: Container)
