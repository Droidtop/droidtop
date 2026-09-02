package dev.droidtop.runtime.linux.root

import dev.droidtop.runtime.RootProcess
import dev.droidtop.runtime.RootProcessResult
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
 * Three changes from upstream DroidSpaces' own usage patterns, all required
 * by the shared-desktop design in docs/SPEC.md:
 *
 *  1. [createPrimary]'s container is where a compositor (sway or labwc —
 *     user-configurable, see docs/SPEC.md §2/§3a) runs as the shared
 *     desktop compositor. [image] is expected to be a plain stock distro
 *     image (§3a: "any OCI image works", no droidtop-maintained custom
 *     build) — this class provisions the compositor into it itself, via
 *     [writeInit]'s embedded `provisionCommand` (see
 *     [dev.droidtop.runtime.CompositorProvisioning]), rather than requiring
 *     a pre-built image. Nothing enforces that the caller actually passed
 *     a PRIMARY-appropriate image/command pair — see
 *     [ContainerRuntime.createPrimary]'s own doc comment.
 *
 *  2. Every container (primary and sibling alike) also gets a real
 *     `/sbin/init` written onto its rootfs by [writeInit] — stock OCI
 *     images ship none, but droidspaces requires one. See that function's
 *     own doc comment for what it writes and why.
 *
 *  3. Sibling containers do NOT use upstream DroidSpaces' own
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

    // [image] is a stock distro image (see docs/SPEC.md §3a's PRIMARY-role
    // entries) with no compositor preinstalled -- [provisionCommand]
    // (see CompositorProvisioning) is embedded into the /sbin/init this
    // class writes onto the pulled rootfs (see [writeInit]) and runs once,
    // on first boot, to actually install one.
    override suspend fun createPrimary(image: RootfsImage, provisionCommand: String?): Container =
        createContainer(name = PRIMARY_NAME, role = ContainerRole.PRIMARY, image = image, provisionCommand = provisionCommand)

    override suspend fun createSibling(image: RootfsImage): Container =
        createContainer(
            name = "droidtop-sibling-${UUID.randomUUID().toString().take(8)}",
            role = ContainerRole.SIBLING,
            image = image,
        )

    private suspend fun createContainer(
        name: String,
        role: ContainerRole,
        image: RootfsImage,
        provisionCommand: String? = null,
    ): Container {
        // Best-effort stale-instance stop BEFORE touching the rootfs, not
        // only in start(): a leaked instance from a force-stopped previous
        // process (see start()'s own comment) still holds droidspaces'
        // mounts over this exact rootfs directory -- confirmed live:
        // writeInit failed with "Read-only file system" on a rootfs two
        // leaked 08-27 processes were still mounted over.
        RootProcess.run(binaryPath, "--name=$name", "stop")
        val rootfsPath = File(rootfsDir, name).absolutePath
        rootfsPuller.pullAndUnpack(image, rootfsPath, imageCache, cachePolicy)
        writeInit(rootfsPath, provisionCommand)

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

    /**
     * Stock OCI images (any distro, any role) ship no real init at all —
     * Docker Hub bases are built for single-process containers, but
     * droidspaces requires a real `/sbin/init` in the rootfs (its own
     * Documentation/Linux-CLI.md: "Must contain /sbin/init"). Written
     * directly onto the pulled rootfs at container-creation time — never
     * baked into any image — matching docs/SPEC.md §2a's "OCI images stay
     * stock, injected at runtime" principle.
     *
     * [provisionCommand] (only ever non-null for the PRIMARY role — see
     * [CompositorProvisioning]) is the chosen distro's own real package-
     * manager command to install a compositor; it runs once, guarded by a
     * marker file, the first time this container actually boots — real
     * network access is required for that (same "host networking, real
     * internet" assumption [CraneRootfsPuller]'s own `crane` calls already
     * depend on), so a fresh primary container's first boot is genuinely
     * slower than later ones. A SIBLING (or a hand-supplied PRIMARY image
     * that already has a compositor — [provisionCommand] null either way)
     * gets a plain idle init instead — matching distrobox's own sibling
     * containers, which sit up doing nothing until something [exec]s into
     * them.
     *
     * UNVERIFIED against a live droidspaces container — no rooted device
     * available in this environment; specifically unconfirmed: that
     * `env_file`'s XDG_RUNTIME_DIR/WAYLAND_DISPLAY are actually exported
     * into this script's environment before it runs (droidspaces' own docs
     * describe the config key but not the exact injection point), and that
     * seatd alone (no systemd-logind) is sufficient for sway's libseat to
     * open a headless session.
     */
    private suspend fun writeInit(rootfsPath: String, provisionCommand: String?) {
        val script = buildString {
            appendLine("#!/bin/sh")
            appendLine("set -e")
            if (provisionCommand != null) {
                appendLine("if [ ! -f /var/lib/droidtop-provisioned ]; then")
                appendLine("  $provisionCommand")
                appendLine("  mkdir -p /var/lib")
                appendLine("  touch /var/lib/droidtop-provisioned")
                appendLine("fi")
                appendLine("mkdir -p /run/seatd")
                appendLine("seatd -g seat &")
                appendLine("export WLR_BACKENDS=headless")
                appendLine("export WLR_LIBINPUT_NO_DEVICES=1")
                appendLine("exec sway")
            } else {
                appendLine("exec sleep infinity")
            }
        }

        val initPath = "$rootfsPath/sbin/init"
        val writeCommand = "mkdir -p '$rootfsPath/sbin' && cat > '$initPath' <<'DROIDTOP_INIT_EOF'\n" +
            script +
            "DROIDTOP_INIT_EOF\nchmod 755 '$initPath'"
        val result = RootProcess.run("sh", "-c", writeCommand)
        check(result.succeeded) { "Writing /sbin/init into $rootfsPath failed: ${result.stderr}" }
    }

    override suspend fun start(container: Container) {
        // Best-effort stop of a stale same-name instance first. Real,
        // confirmed on-device leak this recovers from: droidspaces child
        // processes survive an app force-stop (force-stop skips every
        // Android lifecycle hook, so DesktopSessionService.onDestroy's own
        // reap never runs) -- container names are deterministic
        // (PRIMARY_NAME etc.), so the next session start is the reliable
        // place to reap the previous one instead of double-starting.
        RootProcess.run(binaryPath, "--name=${container.id}", "stop")
        val configPath = File(configsDir, "${container.id}.config").absolutePath
        val result = RootProcess.run(binaryPath, "--conf=$configPath", "start")
        check(result.succeeded) { "droidspaces start failed for ${container.id}: ${result.stderr}" }
    }

    override suspend fun stop(container: Container) {
        val result = RootProcess.run(binaryPath, "--name=${container.id}", "stop")
        check(result.succeeded) { "droidspaces stop failed for ${container.id}: ${result.stderr}" }
    }

    /**
     * The persisted per-container configs under [configsDir] ARE the set of
     * known containers (every create writes one, destroy deletes it);
     * running state comes from droidspaces' own real `show` command (a
     * name+PID table of currently-running containers — simple substring
     * match on the name column is deliberate: names are droidtop-generated
     * and never whitespace-bearing).
     */
    override suspend fun listContainers(): List<dev.droidtop.runtime.ContainerInfo> {
        val configs = configsDir.listFiles { f -> f.isFile && f.name.endsWith(".config") }.orEmpty()
        if (configs.isEmpty()) return emptyList()
        val showOutput = RootProcess.run(binaryPath, "show").stdout
        return configs.map { configFile ->
            val name = configFile.name.removeSuffix(".config")
            val rootfsPath = configFile.readLines()
                .firstOrNull { it.startsWith("rootfs_path=") }
                ?.substringAfter("rootfs_path=")
                ?: File(rootfsDir, name).absolutePath
            dev.droidtop.runtime.ContainerInfo(
                container = Container(
                    id = name,
                    role = if (name == PRIMARY_NAME) ContainerRole.PRIMARY else ContainerRole.SIBLING,
                    backend = backend,
                    rootfsPath = rootfsPath,
                ),
                running = showOutput.lineSequence().any { it.contains(name) },
            )
        }
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
        // Root-owned tree, symlinks inside, possible live bind mounts
        // over it: exactly the job RootfsDelete exists for. A refusal
        // (something still mounted) must fail the destroy loudly --
        // "destroyed" with the rootfs still present would be a lie, and
        // deleting anyway was the shape of the 2026-09-02 data loss.
        val removed = RootfsDelete.delete(container.rootfsPath)
        check(removed.succeeded) {
            "couldn't remove the rootfs for '${container.id}': ${removed.stderr.ifBlank { removed.stdout }}"
        }
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
