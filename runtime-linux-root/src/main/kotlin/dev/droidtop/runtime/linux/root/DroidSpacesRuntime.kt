package dev.droidtop.runtime.linux.root

import android.content.Context
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerBackend
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.ImageCache
import dev.droidtop.runtime.ImageCachePolicy
import dev.droidtop.runtime.RootfsImage
import dev.droidtop.runtime.RootfsPuller
import java.io.File
import java.util.UUID

/**
 * Root-path Linux container backend, driving vendor/droidspaces' `droidspaces`
 * CLI binary (bundled as an APK asset, see [DroidSpacesBinary] — a static
 * musl binary, cross-compiled by build-scripts/build-vendor-deps.sh) as a
 * subprocess via `su -c` ([RootProcess]). Not a JNI/library integration —
 * droidspaces is designed and documented as a command-line tool
 * (Documentation/Linux-CLI.md), so that's the integration surface used here.
 *
 * Two changes from upstream DroidSpaces' own usage patterns, both required
 * by the shared-desktop design in docs/SPEC.md:
 *
 *  1. [createPrimary]'s container is where vendor/sway (headless-output
 *     build) is meant to run as the shared desktop compositor — this class
 *     only handles the container lifecycle; actually installing/starting
 *     sway inside the rootfs is the pulled image's job (see the `TODO` on
 *     [PRIMARY_IMAGE_REFERENCE]), not something orchestrated from here.
 *
 *  2. Sibling containers do NOT use upstream DroidSpaces' own
 *     `--termux-x11`/Termux:X11 auto-launch feature at all — instead, every
 *     container (primary and siblings alike) bind-mounts the SAME host
 *     directory ([socketsDir]) to a fixed in-container path
 *     ([CONTAINER_SOCKET_DIR]), with `XDG_RUNTIME_DIR` pointed at it via an
 *     injected env file. Whichever container's compositor creates the
 *     Wayland socket there (the primary's sway), every other container
 *     bind-mounting the same host directory sees that exact socket file —
 *     this is the actual mechanism distrobox uses on real Linux to share a
 *     host desktop with containers, just expressed through droidspaces'
 *     generic `--bind-mount` primitive instead of a purpose-built flag.
 *     [host-bridge] connects to the same host directory directly, since it
 *     runs as a normal (non-containerized) part of the app.
 *
 * PulseAudio is the one piece where reinventing distrobox's mechanism
 * wasn't necessary: droidspaces already bridges Android's audio HAL to a
 * single host-side PulseAudio daemon and bind-mounts its socket into any
 * container with `enable_pulseaudio=1` — see [DroidSpacesContainerConfig].
 * That's used as-is.
 */
class DroidSpacesRuntime(
    private val context: Context,
    private val rootfsPuller: RootfsPuller,
    private val imageCache: ImageCache,
    private val cachePolicy: ImageCachePolicy,
) : ContainerRuntime {
    override val backend: ContainerBackend = ContainerBackend.DROIDSPACES

    private val binaryPath: String by lazy { DroidSpacesBinary.ensureExtracted(context) }

    private val rootDir = File(context.filesDir, "droidspaces")
    private val configsDir = File(rootDir, "configs")
    private val rootfsDir = File(rootDir, "rootfs")

    /**
     * The host-visible directory every container's `CONTAINER_SOCKET_DIR`
     * bind mount points back to. One per device (not per-container) since
     * there's exactly one primary compositor everything else shares.
     */
    private val socketsDir = File(rootDir, "sockets/primary")

    override suspend fun createPrimary(): Container =
        createContainer(
            name = PRIMARY_NAME,
            role = ContainerRole.PRIMARY,
            // TODO: this needs to actually exist — a published OCI image
            // with vendor/sway (headless build) pre-installed and
            // configured to start on boot with XDG_RUNTIME_DIR/
            // WAYLAND_DISPLAY already pointed at CONTAINER_SOCKET_DIR.
            // Nothing publishes that image yet; RootfsPuller itself also
            // has no implementation yet (see runtime-common/RootfsImage.kt)
            // — both are the actual remaining gap here, not this class's
            // container-orchestration logic, which is real.
            image = RootfsImage(reference = PRIMARY_IMAGE_REFERENCE),
        )

    override suspend fun createSibling(): Container =
        createContainer(
            name = "droidtop-sibling-${UUID.randomUUID().toString().take(8)}",
            role = ContainerRole.SIBLING,
            // TODO: the ContainerRuntime interface doesn't yet let a caller
            // pick which distro a sibling should run (it takes no
            // parameters at all) — real gap, not an oversight here. Falls
            // back to a generic default until that's designed.
            image = RootfsImage(reference = DEFAULT_SIBLING_IMAGE_REFERENCE),
        )

    private suspend fun createContainer(name: String, role: ContainerRole, image: RootfsImage): Container {
        val rootfsPath = File(rootfsDir, name).absolutePath
        rootfsPuller.pullAndUnpack(image, rootfsPath, imageCache, cachePolicy)

        socketsDir.mkdirs()
        val envFile = File(configsDir, "$name.env")
        envFile.parentFile?.mkdirs()
        envFile.writeText(
            "XDG_RUNTIME_DIR=$CONTAINER_SOCKET_DIR\n" +
                "WAYLAND_DISPLAY=$WAYLAND_SOCKET_NAME\n"
        )

        val config = DroidSpacesContainerConfig(
            name = name,
            rootfsPath = rootfsPath,
            bindMounts = listOf(socketsDir.absolutePath to CONTAINER_SOCKET_DIR),
            envFilePath = envFile.absolutePath,
        )
        config.writeTo(File(configsDir, "$name.config"))

        return Container(id = name, role = role, backend = backend, rootfsPath = rootfsPath)
    }

    override suspend fun start(container: Container) {
        val configPath = File(configsDir, "${container.id}.config").absolutePath
        val result = RootProcess.run(binaryPath, "--conf=$configPath", "start")
        check(result.succeeded) { "droidspaces start failed for ${container.id}: ${result.stderr}" }
    }

    override suspend fun stop(container: Container) {
        val result = RootProcess.run(binaryPath, "--name=${container.id}", "stop")
        check(result.succeeded) { "droidspaces stop failed for ${container.id}: ${result.stderr}" }
    }

    override suspend fun destroy(container: Container) {
        // Best-effort stop -- the container may already be stopped, that's
        // not a reason to fail the whole destroy.
        RootProcess.run(binaryPath, "--name=${container.id}", "stop")

        File(configsDir, "${container.id}.config").delete()
        File(configsDir, "${container.id}.env").delete()
        File(container.rootfsPath).deleteRecursively()
    }

    /**
     * Runs droidspaces' own `check` command (verifies kernel namespace/
     * cgroup support) — worth calling before ever attempting createPrimary,
     * so a device that can't actually run containers fails with a clear
     * message instead of a confusing mount/namespace error partway through.
     */
    suspend fun checkSystemRequirements(): RootProcessResult =
        RootProcess.run(binaryPath, "check")

    companion object {
        private const val PRIMARY_NAME = "droidtop-primary"

        private const val CONTAINER_SOCKET_DIR = "/run/droidtop-sockets"
        private const val WAYLAND_SOCKET_NAME = "wayland-0"

        private const val PRIMARY_IMAGE_REFERENCE = "TODO/droidtop-primary:not-published-yet"
        private const val DEFAULT_SIBLING_IMAGE_REFERENCE = "docker.io/library/debian:bookworm"
    }
}
