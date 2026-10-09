package dev.droidtop.runtime.linux.root

import dev.droidtop.runtime.RootProcess
import dev.droidtop.runtime.RootProcessResult
import android.content.Context
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerBackend
import dev.droidtop.runtime.ContainerExecResult
import dev.droidtop.runtime.ContainerDiskUsage
import dev.droidtop.runtime.ContainerLayout
import dev.droidtop.runtime.ContainerNames
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.ContainerSockets
import dev.droidtop.runtime.ExtraMount
import dev.droidtop.runtime.ImageCachePolicy
import dev.droidtop.runtime.PrimaryProvisioning
import dev.droidtop.runtime.RootfsImage
import dev.droidtop.runtime.SharedVolume
import dev.droidtop.runtime.RootfsPuller
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Root-path Linux container backend, driving vendor/droidspaces' `droidspaces`
 * CLI binary (bundled as an APK asset, see [DroidSpacesBinary] — a static
 * musl binary, cross-compiled by build-scripts/build-vendor-deps.sh) as a
 * root subprocess of the elevated helper, Shizuku or Sui ([RootProcess];
 * droidtop never runs `su`). Not a JNI/library integration —
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
 *     build) — this class provisions the compositor into it itself: the
 *     `/sbin/init` [writeInit] writes is
 *     [ContainerLayout.primaryInitScript], the same boot script the proot
 *     backend runs. Nothing enforces that the caller actually passed a
 *     PRIMARY-appropriate image/plan pair — see
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
 *     ([ContainerLayout.SOCKET_DIR]), with `XDG_RUNTIME_DIR` pointed at it via an
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
    private val cachePolicy: ImageCachePolicy,
) : ContainerRuntime {
    override val backend: ContainerBackend = ContainerBackend.DROIDSPACES

    private val binaryPath: String by lazy { DroidSpacesBinary.ensureExtracted(context) }

    private val rootDir = File(context.filesDir, "droidspaces")
    private val configsDir = File(rootDir, "configs")
    private val rootfsDir = File(rootDir, "rootfs")
    private val names = ContainerNames(File(rootDir, ContainerNames.FILE_NAME))

    /**
     * The host-visible directory every container's `ContainerLayout.SOCKET_DIR`
     * bind mount points back to. One per device (not per-container) since
     * there's exactly one primary compositor everything else shares.
     */
    private val socketsDir = File(rootDir, "sockets/primary")

    /**
     * The app's own private-storage root (`Context.getFilesDir()`), bind-
     * mounted read-write into every container at [ContainerLayout.APP_STORAGE_DIR]
     * so a host path under it — most notably gamenative's own per-container
     * Wine prefixes, see [ContainerRuntime.hostStorageToContainerPath]'s own
     * doc comment — is actually reachable from inside the container's mount
     * namespace, not just on the Android host side.
     */
    private val appStorageDir = context.filesDir

    // [image] is a stock distro image (see docs/SPEC.md §3a's PRIMARY-role
    // entries) with no compositor preinstalled -- [provisioning] (see
    // CompositorProvisioning) is embedded into the /sbin/init this class
    // writes onto the pulled rootfs (see [writeInit]) and runs once, on
    // first boot, to actually install one.
    override suspend fun createPrimary(image: RootfsImage, provisioning: PrimaryProvisioning): Container =
        createContainer(name = PRIMARY_NAME, role = ContainerRole.PRIMARY, image = image, provisioning = provisioning)

    override suspend fun createSibling(image: RootfsImage, name: String?): Container {
        val existing = listContainers()
        val chosen = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: ContainerNames.defaultName(ContainerRole.SIBLING, image.reference, existing.map { it.displayName })
        ContainerNames.problemWith(chosen, existing.map { it.displayName })?.let { error(it) }
        val container = createContainer(
            name = "droidtop-sibling-${UUID.randomUUID().toString().take(8)}",
            role = ContainerRole.SIBLING,
            image = image,
        )
        withContext(Dispatchers.IO) { names.set(container.id, chosen) }
        return container
    }

    override suspend fun rename(container: Container, name: String) {
        val existing = listContainers()
        withContext(Dispatchers.IO) { names.rename(container.id, name, existing) }
    }

    private suspend fun createContainer(
        name: String,
        role: ContainerRole,
        image: RootfsImage,
        provisioning: PrimaryProvisioning? = null,
    ): Container {
        // Best-effort stale-instance stop BEFORE touching the rootfs, not
        // only in start(): a leaked instance from a force-stopped previous
        // process (see start()'s own comment) still holds droidspaces'
        // mounts over this exact rootfs directory -- confirmed live:
        // writeInit failed with "Read-only file system" on a rootfs two
        // leaked 08-27 processes were still mounted over.
        RootProcess.run(binaryPath, "--name=$name", "stop")
        val rootfsPath = File(rootfsDir, name).absolutePath
        rootfsPuller.pullAndUnpack(image, rootfsPath, cachePolicy)
        writeInit(rootfsPath, provisioning)
        withContext(Dispatchers.IO) { writeImageRecord(name, image) }
        if (provisioning != null) withContext(Dispatchers.IO) { writeProvisioning(name, provisioning) }

        writeConfig(name, rootfsPath)

        return Container(id = name, role = role, backend = backend, rootfsPath = rootfsPath)
    }

    /**
     * A fresh pull of the same image reference, kept name and role, the
     * PRIMARY's provisioning plan carried over (docs/SPEC.md 3d "Recreate
     * from the image"). UNVERIFIED against a live droidspaces container --
     * no rooted device available in this environment; same caveat as the
     * rest of this class.
     */
    override suspend fun recreateFromImage(container: Container): Container {
        val info = listContainers().firstOrNull { it.container.id == container.id }
            ?: error("${container.id} no longer exists")
        val imageRef = info.image ?: error("${container.id} has no recorded image reference to recreate from")
        val provisioning = if (container.role == ContainerRole.PRIMARY) readProvisioning(container.id) else null
        val displayName = info.displayName
        destroy(container)
        val created = createContainer(container.id, container.role, RootfsImage(reference = imageRef), provisioning)
        if (container.role == ContainerRole.SIBLING) {
            withContext(Dispatchers.IO) { names.set(created.id, displayName) }
        }
        return created
    }

    /**
     * The droidspaces `.config` for [name]: the shared socket directory,
     * app storage, and every shared-storage volume mounted right now
     * ([SharedVolume.mounted], docs/SPEC.md 4b). Written at creation and
     * again at every [start], so a card or USB drive mounted since the
     * container was made is bound on its next boot.
     */
    private fun writeConfig(name: String, rootfsPath: String) {
        socketsDir.mkdirs()
        val envFile = File(configsDir, "$name.env")
        envFile.parentFile?.mkdirs()
        // The socket's name is only known once the compositor has made it,
        // so WAYLAND_DISPLAY is added per exec (see [exec]), not here.
        // audioShared is false here regardless of this container's own
        // setting: PULSE_SERVER is this backend's own to set, exported by
        // droidspaces' own environment setup once its bridge's socket
        // exists (vendor/droidspaces src/environment.c, src/android/
        // pulseaudio.c) -- ContainerLayout's PULSE_SERVER is proot's
        // HostAudioServer path, which droidspaces does not use.
        envFile.writeText(ContainerLayout.clientEnvironment(null, audioShared = false).entries.joinToString("") { (key, value) -> "$key=$value\n" })

        val sockets = readSockets(name)
        val config = DroidSpacesContainerConfig(
            name = name,
            rootfsPath = rootfsPath,
            bindMounts = listOf(
                socketsDir.absolutePath to ContainerLayout.SOCKET_DIR,
                appStorageDir.absolutePath to ContainerLayout.APP_STORAGE_DIR,
            ) + ContainerLayout.sharedStorageBinds(SharedVolume.mounted(context)) +
                // Device nodes the person chose on the container's Devices
                // row, each at its own path. One that is gone (unplugged)
                // is skipped by droidspaces with a warning (mount.c, "Failed
                // to bind mount ... (skipping)"), not a failed start.
                devicesFile(name).takeIf { it.isFile }?.readLines().orEmpty()
                    .filter { it.isNotBlank() }
                    .map { it to it } +
                // The person's own extra Mounts (docs/SPEC.md 3d), each at
                // its own path -- droidspaces skips a bind whose host side
                // is gone the same way it skips an unplugged device.
                readExtraMounts(name).filter { File(it.hostPath).isDirectory }.map { it.hostPath to it.containerPath },
            envFilePath = envFile.absolutePath,
            // The one place this backend already bridges host audio in --
            // droidspaces' own PulseAudio bridge, used as-is (see this
            // class's doc comment). Off skips the bridge entirely, unlike
            // Wayland (below) which only withholds the client env var.
            enablePulseAudio = sockets.audioShared,
        )
        config.writeTo(File(configsDir, "$name.config"))
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
     * The PRIMARY's init is [ContainerLayout.primaryInitScript]: provision
     * once (real network access required, the same "host networking, real
     * internet" assumption crane's pulls already depend on), then exec the
     * compositor headless. It exports its own `XDG_RUNTIME_DIR`, so it does
     * not depend on droidspaces' `env_file` reaching init. A SIBLING gets a
     * plain idle init instead — matching distrobox's own sibling
     * containers, which sit up doing nothing until something [exec]s into
     * them.
     *
     * UNVERIFIED against a live droidspaces container — no rooted device
     * available in this environment. It is the same script the proot backend
     * runs (docs/SPEC.md §3).
     */
    private suspend fun writeInit(rootfsPath: String, provisioning: PrimaryProvisioning?) {
        val script = provisioning?.let { ContainerLayout.primaryInitScript(it) }
            ?: "#!/bin/sh\nexec sleep infinity\n"

        // The rootfs path rides in as "$1", never spliced into the script:
        // this runs as root, and a quote in the path must stay a quote.
        val writeCommand = "mkdir -p \"\$1/sbin\" && cat > \"\$1/sbin/init\" <<'DROIDTOP_INIT_EOF'\n" +
            script +
            "DROIDTOP_INIT_EOF\nchmod 755 \"\$1/sbin/init\""
        val result = RootProcess.run("sh", "-c", writeCommand, "sh", rootfsPath)
        check(result.succeeded) { "Writing /sbin/init into $rootfsPath failed: ${result.stderr}" }
    }

    override suspend fun start(container: Container, provisioning: PrimaryProvisioning?, onProgress: (String) -> Unit) {
        // The current plan replaces the /sbin/init written at creation.
        if (provisioning != null && container.role == ContainerRole.PRIMARY) {
            writeInit(container.rootfsPath, provisioning)
            withContext(Dispatchers.IO) { writeProvisioning(container.id, provisioning) }
        }
        // Best-effort stop of a stale same-name instance first. Real,
        // confirmed on-device leak this recovers from: droidspaces child
        // processes survive an app force-stop (force-stop skips every
        // Android lifecycle hook, so DesktopSessionService.onDestroy's own
        // reap never runs) -- container names are deterministic
        // (PRIMARY_NAME etc.), so the next session start is the reliable
        // place to reap the previous one instead of double-starting.
        RootProcess.run(binaryPath, "--name=${container.id}", "stop")
        writeConfig(container.id, container.rootfsPath)
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
        return names.named(configs.map { configFile ->
            val name = configFile.name.removeSuffix(".config")
            val rootfsPath = configFile.readLines()
                .firstOrNull { it.startsWith("rootfs_path=") }
                ?.substringAfter("rootfs_path=")
                ?: File(rootfsDir, name).absolutePath
            val (imageRef, digest) = readImageRecord(name)
            dev.droidtop.runtime.ContainerInfo(
                container = Container(
                    id = name,
                    role = if (name == PRIMARY_NAME) ContainerRole.PRIMARY else ContainerRole.SIBLING,
                    backend = backend,
                    rootfsPath = rootfsPath,
                ),
                running = showOutput.lineSequence().any { it.contains(name) },
                image = imageRef,
                digest = digest,
            )
        })
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
        val waylandSocketName = if (readSockets(container.id).waylandShared) {
            ContainerLayout.findWaylandSocket(socketsDir)?.name
        } else {
            null
        }
        // Same reasoning as writeConfig: PULSE_SERVER is droidspaces' own
        // to set, not ContainerLayout's.
        val fullEnv = ContainerLayout.clientEnvironment(waylandSocketName, audioShared = false) + env
        val envPrefix = fullEnv.entries.joinToString(" ") { (k, v) -> "$k=${shellQuote(v)}" }
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
        imageRecordFile(container.id).delete()
        provisioningFile(container.id).delete()
        socketsFile(container.id).delete()
        mountsFile(container.id).delete()
        names.remove(container.id)
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
    override suspend fun checkSystemRequirements(): ContainerExecResult {
        val result: RootProcessResult = RootProcess.run(binaryPath, "check")
        return ContainerExecResult(exitCode = result.exitCode, stdout = result.stdout, stderr = result.stderr)
    }

    override fun primaryWaylandSocketPath(): String =
        (ContainerLayout.findWaylandSocket(socketsDir) ?: error("the primary compositor has no socket in ${socketsDir.path}"))
            .absolutePath

    override fun hostSocketDir(): File = socketsDir

    override val deviceSharingUnavailableReason: String? = null

    private fun devicesFile(name: String) = File(configsDir, "$name.devices")

    override suspend fun sharedDevices(container: Container): List<String> = withContext(Dispatchers.IO) {
        devicesFile(container.id).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty()
    }

    /** Written beside the container's config; [writeConfig] binds each on the next start. */
    override suspend fun setSharedDevices(container: Container, devicePaths: List<String>) = withContext(Dispatchers.IO) {
        require(devicePaths.all { it.startsWith("/dev/") && !it.contains("..") && !it.contains(',') && !it.contains(':') }) {
            "not a device node path: $devicePaths"
        }
        devicesFile(container.id).apply { parentFile?.mkdirs() }.writeText(devicePaths.joinToString("") { "$it\n" })
    }

    // ---- sockets (docs/SPEC.md 3d) ----

    private fun socketsFile(name: String) = File(configsDir, "$name.sockets")

    private fun readSockets(name: String): ContainerSockets {
        val lines = socketsFile(name).takeIf { it.isFile }?.readLines().orEmpty().associate { line ->
            val (key, value) = line.split("=", limit = 2).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
            key to value
        }
        return ContainerSockets(
            waylandShared = lines["wayland"]?.toBooleanStrictOrNull() ?: true,
            audioShared = lines["audio"]?.toBooleanStrictOrNull() ?: true,
        )
    }

    override suspend fun sockets(container: Container): ContainerSockets = withContext(Dispatchers.IO) { readSockets(container.id) }

    override suspend fun setSockets(container: Container, sockets: ContainerSockets) = withContext(Dispatchers.IO) {
        socketsFile(container.id).apply { parentFile?.mkdirs() }
            .writeText("wayland=${sockets.waylandShared}\naudio=${sockets.audioShared}\n")
        Unit
    }

    // Real: droidspaces bridges Android's audio HAL to a host PulseAudio
    // daemon and binds it into any container with enable_pulseaudio=1 --
    // see this class's own doc comment and [writeConfig].
    override val audioSharingUnavailableReason: String? = null

    // ---- extra mounts (docs/SPEC.md 3d) ----

    private fun mountsFile(name: String) = File(configsDir, "$name.mounts")

    private fun readExtraMounts(name: String): List<ExtraMount> =
        mountsFile(name).takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) ExtraMount(parts[0], parts[1]) else null
            }

    override suspend fun extraMounts(container: Container): List<ExtraMount> = withContext(Dispatchers.IO) { readExtraMounts(container.id) }

    override suspend fun setExtraMounts(container: Container, mounts: List<ExtraMount>) = withContext(Dispatchers.IO) {
        mountsFile(container.id).apply { parentFile?.mkdirs() }
            .writeText(mounts.joinToString("") { "${it.hostPath}\t${it.name}\n" })
        Unit
    }

    // ---- image reference + provisioning plan, for recreateFromImage ----

    private fun imageRecordFile(name: String) = File(configsDir, "$name.image")

    private fun writeImageRecord(name: String, image: RootfsImage) {
        imageRecordFile(name).apply { parentFile?.mkdirs() }
            .writeText(image.reference + "\n" + (image.digest ?: ""))
    }

    private fun readImageRecord(name: String): Pair<String?, String?> {
        val lines = imageRecordFile(name).takeIf { it.isFile }?.readLines() ?: return null to null
        return lines.getOrNull(0) to lines.getOrNull(1)?.ifBlank { null }
    }

    private fun provisioningFile(name: String) = File(configsDir, "$name.provisioning")

    /** Same three fields as ProotRuntime's Properties-backed equivalent, one per line. */
    private fun writeProvisioning(name: String, provisioning: PrimaryProvisioning) {
        provisioningFile(name).apply { parentFile?.mkdirs() }
            .writeText(
                provisioning.installCommand + "\u0000" +
                    provisioning.compositorCommand + "\u0000" +
                    provisioning.daemons.joinToString(";"),
            )
    }

    private fun readProvisioning(name: String): PrimaryProvisioning? {
        val text = provisioningFile(name).takeIf { it.isFile }?.readText() ?: return null
        val parts = text.split("\u0000")
        if (parts.size < 2) return null
        val daemons = parts.getOrElse(2) { "" }.split(";").filter { it.isNotBlank() }
        return PrimaryProvisioning(parts[0], parts[1], daemons)
    }

    override fun hostStorageToContainerPath(hostPath: File): String =
        ContainerLayout.hostStorageToContainerPath(appStorageDir, hostPath)

    companion object {
        private const val PRIMARY_NAME = "droidtop-primary"
    }
}
