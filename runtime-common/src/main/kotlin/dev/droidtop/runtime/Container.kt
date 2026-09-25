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
 * (docs/SPEC.md §3d's container manager is the real consumer). [image] is
 * the reference it was made from and [digest] the digest that pinned it,
 * where the backend recorded them.
 */
data class ContainerInfo(
    val container: Container,
    val running: Boolean,
    val image: String? = null,
    val digest: String? = null,
)

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
     * image with no compositor preinstalled — [provisioning] (see
     * [CompositorProvisioning]) is what a backend runs, once, on the
     * container's first boot to install one, and the compositor it then
     * starts ([ContainerLayout.primaryInitScript]), so the same "any OCI
     * image works" story (§3a) holds for the PRIMARY role too, not just
     * siblings. This interface doesn't validate the pairing; the caller is
     * responsible for picking a PRIMARY-appropriate entry and its plan.
     */
    suspend fun createPrimary(image: RootfsImage, provisioning: PrimaryProvisioning): Container

    /** [image] is any SIBLING/BOTH-appropriate reference — no compositor needed. */
    suspend fun createSibling(image: RootfsImage): Container

    /**
     * Boots [container]. For the PRIMARY this returns once the compositor's
     * socket accepts connections (a first boot provisions it first, which
     * can take many minutes); [onProgress] receives human-readable lines
     * about what it is waiting on, for a caller that shows them. A backend
     * with nothing to report never calls it.
     *
     * [provisioning], for the PRIMARY, is the plan to boot with, replacing
     * the one recorded at creation: the plan is the catalog entry's
     * current one, and a container made earlier must pick up what has
     * been added to it since (a font, in practice: dq-desktop-07's reused
     * container provisioned with its creation-time command and had none).
     * The boot script re-runs the install whenever the plan it last
     * completed differs ([ContainerLayout.primaryInitScript]). Null keeps
     * the recorded plan.
     */
    suspend fun start(container: Container, provisioning: PrimaryProvisioning? = null, onProgress: (String) -> Unit = {})

    /**
     * Ends everything running in [container]: for the PRIMARY its
     * compositor and every program on the desktop, for a sibling every
     * program running in it. Returns once they are gone.
     */
    suspend fun stop(container: Container)

    /**
     * Whether a SIBLING has to be started before [exec] can run in it.
     * droidspaces boots a sibling's own init; under proot a sibling has
     * none, so it is only ever its programs and "start" does nothing a
     * person could see (the container manager offers no Start for it).
     */
    val siblingsNeedStart: Boolean get() = true

    /**
     * Whether this device can run this backend at all, answered by running
     * something real rather than inferred: droidspaces' own `check`
     * (namespaces, cgroups), or a trivial process under proot (ptrace and
     * the packaged loader). A failure's output says why.
     */
    suspend fun checkSystemRequirements(): ContainerExecResult
    suspend fun destroy(container: Container)

    /**
     * Runs [command] as a process inside an already-running [container] —
     * the primitive a native-Linux-depot launch (§5a) needs: something
     * that actually starts a process *inside* the container, as opposed
     * to the container lifecycle operations above. [env] is merged into
     * the process' environment (e.g. `WAYLAND_DISPLAY`).
     *
     * Windows software deliberately does NOT come through here. Wine
     * runs through `:runtime-windows`'s own `WineEngine`, against the
     * ImageFs in app storage, with no container and no root — see
     * docs/SPEC.md 5b. A droidspaces `exec` may become a desktop-mode
     * optimisation for it later; it is not a precondition.
     */
    suspend fun exec(container: Container, command: List<String>, env: Map<String, String> = emptyMap()): ContainerExecResult

    /**
     * Why this backend cannot share a device node (a USB serial adapter, a
     * scanner) with a container, in words for the container manager's
     * Devices row; null when it can (docs/SPEC.md 4b).
     */
    val deviceSharingUnavailableReason: String?

    /** The host device nodes bound into [container], by path (`/dev/bus/usb/001/004`). */
    suspend fun sharedDevices(container: Container): List<String>

    /**
     * Records the device nodes bound into [container], each at the same
     * path inside it; they are bound from its next start. Only called
     * when [deviceSharingUnavailableReason] is null.
     */
    suspend fun setSharedDevices(container: Container, devicePaths: List<String>)

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
     * The host side of [ContainerLayout.SOCKET_DIR]: the one directory
     * every container this backend runs sees there. A socket a container
     * serves at `SOCKET_DIR/<name>` is the file `<this>/<name>` to
     * droidtop, which is how the device VPN reaches
     * [ContainerLayout.VPN_SOCKET] (docs/SPEC.md 4a).
     */
    fun hostSocketDir(): File

    /**
     * Translates a host-visible path under the app's own private storage
     * (`Context.getFilesDir()` or a subtree of it) into the equivalent
     * path visible *inside* a running container.
     *
     * Needed because a native Linux game installed by droidtop lives in
     * app storage, not inside the container's own rootfs, and [exec]
     * runs inside the container's mount namespace. Backends that isolate
     * containers behind a real mount namespace can't just hand that host
     * path to `exec` unmodified; they instead bind-mount the whole
     * app-storage directory into every container they create at a fixed
     * in-container path (see DroidSpacesRuntime's own doc comment) and
     * this method does the prefix substitution. [hostPath] must be under
     * the app's private storage root — passing anything else is a caller
     * bug.
     */
    fun hostStorageToContainerPath(hostPath: File): String
}

/**
 * The live primary [Container] + the [ContainerRuntime] that owns it — what
 * a Wayland-client workload (a native Linux game, in particular) needs to
 * actually launch alongside the running desktop. Owned by `:app`'s
 * `DesktopSessionService`; exposed as
 * this plain value type (rather than `runtime-windows` depending on `:app`'s
 * `DesktopSessionState` directly, which would be a circular module
 * dependency — `:app` depends on `:runtime-windows`, not the reverse).
 */
data class PrimaryContainerSession(val runtime: ContainerRuntime, val container: Container)
