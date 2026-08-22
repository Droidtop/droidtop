package dev.droidtop.runtime.linux.root

import android.content.Context
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerBackend
import dev.droidtop.runtime.ContainerExecResult
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
 *  1. [createPrimary]'s container is where a compositor (sway or labwc —
 *     user-configurable, see docs/SPEC.md §2/§3a) is meant to run as the
 *     shared desktop compositor — this class only handles the container
 *     lifecycle; actually installing/starting the compositor inside the
 *     rootfs is the caller-supplied image's job, not something orchestrated
 *     from here. Nothing enforces that the caller actually passed a
 *     PRIMARY-appropriate image — see [ContainerRuntime.createPrimary]'s
 *     own doc comment.
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

    /**
     * The app's own private-storage root (`Context.getFilesDir()`), bind-
     * mounted read-write into every container at [CONTAINER_APP_STORAGE_DIR]
     * so a host path under it — most notably gamenative's own per-container
     * Wine prefixes, see [ContainerRuntime.hostStorageToContainerPath]'s own
     * doc comment — is actually reachable from inside the container's mount
     * namespace, not just on the Android host side.
     */
    private val appStorageDir = context.filesDir

    // [image] must actually have a compositor pre-installed and configured
    // to start on boot with XDG_RUNTIME_DIR/WAYLAND_DISPLAY already pointed
    // at CONTAINER_SOCKET_DIR (see docs/SPEC.md §3a's PRIMARY-role entries)
    // — this class doesn't validate that, it just pulls whatever reference
    // the caller hands it and boots the container.
    override suspend fun createPrimary(image: RootfsImage): Container =
        createContainer(name = PRIMARY_NAME, role = ContainerRole.PRIMARY, image = image)

    override suspend fun createSibling(image: RootfsImage): Container =
        createContainer(
            name = "droidtop-sibling-${UUID.randomUUID().toString().take(8)}",
            role = ContainerRole.SIBLING,
            image = image,
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
            bindMounts = listOf(
                socketsDir.absolutePath to CONTAINER_SOCKET_DIR,
                appStorageDir.absolutePath to CONTAINER_APP_STORAGE_DIR,
            ),
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

    /**
     * `droidspaces --name=<id> run <cmd...>` — droidspaces' own documented
     * exec-into-running-container primitive (Documentation/Linux-CLI.md's
     * `run` subcommand). Per-invocation env vars aren't a `run` flag
     * droidspaces exposes (only a per-container `env_file` at container-
     * config time, already used in [createContainer] for
     * XDG_RUNTIME_DIR/WAYLAND_DISPLAY) — [env] is instead prepended as
     * inline POSIX shell assignments, the same pattern droidspaces' own
     * CLI docs use (`run sh -c "id && env"`).
     */
    override suspend fun exec(container: Container, command: List<String>, env: Map<String, String>): ContainerExecResult {
        val envPrefix = env.entries.joinToString(" ") { (k, v) -> "$k=${shellQuote(v)}" }
        val commandLine = command.joinToString(" ") { shellQuote(it) }
        val shellScript = if (envPrefix.isEmpty()) commandLine else "$envPrefix $commandLine"

        val result = RootProcess.run(binaryPath, "--name=${container.id}", "run", "sh", "-c", shellScript)
        return ContainerExecResult(exitCode = result.exitCode, stdout = result.stdout, stderr = result.stderr)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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

    override fun primaryWaylandSocketPath(): String =
        File(socketsDir, WAYLAND_SOCKET_NAME).absolutePath

    override fun hostStorageToContainerPath(hostPath: File): String {
        val relative = hostPath.absoluteFile.toRelativeString(appStorageDir.absoluteFile)
        require(!relative.startsWith("..")) { "$hostPath isn't under the app storage root $appStorageDir" }
        return "$CONTAINER_APP_STORAGE_DIR/$relative"
    }

    companion object {
        private const val PRIMARY_NAME = "droidtop-primary"

        private const val CONTAINER_SOCKET_DIR = "/run/droidtop-sockets"
        private const val CONTAINER_APP_STORAGE_DIR = "/run/droidtop-app-storage"
        private const val WAYLAND_SOCKET_NAME = "wayland-0"
    }
}
