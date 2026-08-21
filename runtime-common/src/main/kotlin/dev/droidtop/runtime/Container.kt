package dev.droidtop.runtime

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

/** Common lifecycle surface both container backends implement. */
interface ContainerRuntime {
    val backend: ContainerBackend

    /**
     * [image] is caller-chosen — from the live-resolved catalog
     * ([ResolvedImage.toRootfsImage], see docs/SPEC.md §3a) or a hand-typed
     * custom OCI reference alike. For the PRIMARY role this must be an
     * image with a compositor pre-installed (a stock distro image has
     * none); this interface doesn't validate that — the caller is
     * responsible for picking a PRIMARY-appropriate entry.
     */
    suspend fun createPrimary(image: RootfsImage): Container

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
}
