package dev.droidtop.runtime.linux.root

import dev.droidtop.runtime.RootProcess
import dev.droidtop.runtime.RootProcessResult
import android.content.Context
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerBackend
import dev.droidtop.runtime.ContainerExecResult
import dev.droidtop.runtime.ContainerDiskUsage
import dev.droidtop.runtime.ClipboardFilters
import dev.droidtop.runtime.ContainerLayout
import dev.droidtop.runtime.ContainerNames
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.ContainerLook
import dev.droidtop.runtime.ContainerSockets
import dev.droidtop.runtime.ContainerUser
import dev.droidtop.runtime.EnvironmentMounts
import dev.droidtop.runtime.ImageRunConfig
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
        mounts: EnvironmentMounts = EnvironmentMounts.NEW_CONTAINER,
    ): Container {
        // Best-effort stale-instance stop BEFORE touching the rootfs, not
        // only in start(): a leaked instance from a force-stopped previous
        // process (see start()'s own comment) still holds droidspaces'
        // mounts over this exact rootfs directory -- confirmed live:
        // writeInit failed with "Read-only file system" on a rootfs two
        // leaked 08-27 processes were still mounted over.
        RootProcess.run(binaryPath, "--name=$name", "stop")
        val rootfsPath = File(rootfsDir, name).absolutePath
        val runConfig = rootfsPuller.pullAndUnpack(image, rootfsPath, cachePolicy)
        runConfig?.let { withContext(Dispatchers.IO) { runConfigFile(name).apply { parentFile?.mkdirs() }.writeText(ImageRunConfig.encode(it)) } }
        writeInit(rootfsPath, provisioning)
        withContext(Dispatchers.IO) { writeImageRecord(name, image) }
        if (provisioning != null) withContext(Dispatchers.IO) { writeProvisioning(name, provisioning) }
        // A new container shares the home; a recreated one is handed the
        // home and folders it had ([recreateFromImage]).
        withContext(Dispatchers.IO) { writeMounts(name, mounts) }

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
        // The home and shared folders are the person's choice, not the image's: they survive a recreate.
        val mounts = withContext(Dispatchers.IO) { readMounts(container.id) }
        destroy(container)
        val created = createContainer(container.id, container.role, RootfsImage(reference = imageRef), provisioning, mounts)
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
        envFile.writeText(ContainerLayout.clientEnvironment(null, audioShared = false, look = readLook(name)).entries.joinToString("") { (key, value) -> "$key=$value\n" })

        val sockets = readSockets(name)
        val config = DroidSpacesContainerConfig(
            name = name,
            rootfsPath = rootfsPath,
            bindMounts = listOf(
                socketsDir.absolutePath to ContainerLayout.SOCKET_DIR,
                appStorageDir.absolutePath to ContainerLayout.APP_STORAGE_DIR,
            ) + clipboardBinds(name, sockets) + ContainerLayout.sharedStorageBinds(SharedVolume.mounted(context)) +
                // Device nodes the person chose on the container's Devices
                // row, each at its own path. One that is gone (unplugged)
                // is skipped by droidspaces with a warning (mount.c, "Failed
                // to bind mount ... (skipping)"), not a failed start.
                devicesFile(name).takeIf { it.isFile }?.readLines().orEmpty()
                    .filter { it.isNotBlank() }
                    .map { it to it } +
                // The container's home and the person's own extra folders
                // (docs/SPEC.md 3d "Home and folders"), from the one
                // description both backends read; a folder that is gone is
                // left out.
                readMounts(name).binds(ContainerLayout.sharedHomeDir(appStorageDir)),
            envFilePath = envFile.absolutePath,
            // The one place this backend already bridges host audio in --
            // droidspaces' own PulseAudio bridge, used as-is (see this
            // class's doc comment). Off skips the bridge entirely, unlike
            // Wayland (below) which only withholds the client env var.
            enablePulseAudio = sockets.audioShared,
            // Its network taken away on the container's page (docs/SPEC.md 3d "Network"). The desktop's sockets are
            // files in a bound folder, which no network namespace touches.
            noNetwork = !readNetworkShared(name),
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

    override val usageUnavailableReason: String? = null

    /**
     * A droidspaces container's processes are root's, so they are read through the root shell (Shizuku or Sui): its
     * init's PID from droidspaces' own `pid` command, then every process in that PID namespace, twice [sampleMs]
     * apart ([ProcUsage.namespaceScript]). Without the root shell this throws, and the row says why.
     */
    override suspend fun usage(container: Container, sampleMs: Long): dev.droidtop.runtime.ContainerUsage? {
        val pid = RootProcess.run(binaryPath, "--name=${container.id}", "pid")
        check(pid.launched) { NO_ROOT_SHELL }
        val init = pid.stdout.trim().toIntOrNull() ?: return null
        suspend fun sample(): List<dev.droidtop.runtime.ProcUsage.Process> {
            val result = RootProcess.run("/system/bin/sh", "-c", dev.droidtop.runtime.ProcUsage.namespaceScript(init))
            check(result.launched) { NO_ROOT_SHELL }
            return dev.droidtop.runtime.ProcUsage.parse(result.stdout)
        }
        val first = sample()
        if (first.isEmpty()) return null
        val started = android.os.SystemClock.elapsedRealtime()
        kotlinx.coroutines.delay(sampleMs)
        val second = sample()
        return dev.droidtop.runtime.ProcUsage.usage(
            first, second, android.os.SystemClock.elapsedRealtime() - started,
            dev.droidtop.runtime.ProcUsage.ticksPerSecond(), Runtime.getRuntime().availableProcessors(),
        )
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
    override suspend fun exec(
        container: Container,
        command: List<String>,
        env: Map<String, String>,
        user: ContainerUser.Spec?,
    ): ContainerExecResult {
        // Another user (Droidtop/tracker#715): real root switches with the image's own `su`, at the end of droidtop's
        // script (ContainerUser.EXEC_TAIL), after its redirections. `su` takes a name and gives that user's own group.
        // The tree is root's, so its two files are read inside the container, where its own root applies.
        val switchTo = user?.takeUnless { it.isRoot }?.let { spec ->
            val passwd = RootProcess.run(binaryPath, "--name=${container.id}", "run", "cat", "/etc/passwd").stdout
            val group = RootProcess.run(binaryPath, "--name=${container.id}", "run", "cat", "/etc/group").stdout
            val resolved = ContainerUser.resolve(spec, passwd, group).getOrThrow()
            val name = resolved.name
                ?: throw IllegalArgumentException("uid ${spec.user} has no line in the image's /etc/passwd, which the rooted backend's su needs")
            val own = ContainerUser.resolve(spec.copy(group = null), passwd, group).getOrThrow().gid
            if (resolved.gid != own) {
                throw IllegalArgumentException("a group other than $name's own (${spec.group}) is not built on the rooted backend")
            }
            name
        }
        val sockets = readSockets(container.id)
        val waylandShared = sockets.waylandShared
        val waylandSocketName = if (waylandShared) ContainerLayout.findWaylandSocket(socketsDir)?.name else null
        // A sibling with its clipboard off has sway's IPC blanked ([clipboardBinds]), so SWAYSOCK would name nothing.
        val clipboardFiltered = !sockets.clipboardShared && container.id != PRIMARY_NAME
        val swaySocketName = if (waylandShared && !clipboardFiltered) ContainerLayout.findSwayIpcSocket(socketsDir)?.name else null
        // Same reasoning as writeConfig: PULSE_SERVER is droidspaces' own
        // to set, not ContainerLayout's.
        val fullEnv = ContainerLayout.clientEnvironment(waylandSocketName, audioShared = false, swaySocketName = swaySocketName, look = readLook(container.id)) + env +
            (switchTo?.let { mapOf(ContainerUser.SWITCH_ENV to it) } ?: emptyMap())
        val envPrefix = fullEnv.entries.joinToString(" ") { (k, v) -> "$k=${shellQuote(v)}" }
        val commandLine = command.joinToString(" ") { shellQuote(it) }
        val shellScript = if (envPrefix.isEmpty()) commandLine else "$envPrefix $commandLine"

        val result = RootProcess.run(binaryPath, "--name=${container.id}", "run", "sh", "-c", shellScript)
        return ContainerExecResult(exitCode = result.exitCode, stdout = result.stdout, stderr = result.stderr)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    override val dataBackupUnavailableReason: String? = null

    /**
     * The container's whole tree as a tar archive, written as root (docs/SPEC.md 3d "Backup"; Droidtop/tracker#758):
     * it is stopped first, as on proot, so nothing changes under tar.
     */
    override suspend fun exportData(container: Container, out: java.io.OutputStream) {
        RootProcess.run(binaryPath, "--name=${container.id}", "stop")
        RootArchive.export(container.rootfsPath, out)
    }

    private fun archiver(container: Container) =
        dev.droidtop.runtime.IncrementalArchiver(container.rootfsPath, RootIncrementTools(java.io.File(context.cacheDir, "backup-scratch")))

    override suspend fun exportIncrement(container: Container, minutes: Long, archive: java.io.OutputStream, manifest: java.io.OutputStream) {
        RootProcess.run(binaryPath, "--name=${container.id}", "stop")
        withContext(Dispatchers.IO) { archiver(container).exportChanged(minutes, archive, manifest) }
    }

    override suspend fun applyIncrement(container: Container, archive: java.io.InputStream) {
        RootProcess.run(binaryPath, "--name=${container.id}", "stop")
        withContext(Dispatchers.IO) { archiver(container).apply(archive) }
    }

    override suspend fun pruneToManifest(container: Container, manifest: String) {
        withContext(Dispatchers.IO) { archiver(container).pruneTo(manifest) }
    }

    /**
     * Replaces the container's tree with the archive on [input], as proot's restore does: unpacked beside it first,
     * checked to be a container's filesystem, then swapped in, with the old tree put back if the swap fails. As root,
     * so owners and modes come back as they were.
     */
    override suspend fun importData(container: Container, input: java.io.InputStream) {
        RootProcess.run(binaryPath, "--name=${container.id}", "stop")
        val rootfs = container.rootfsPath
        val staged = "$rootfs.restore"
        val previous = "$rootfs.previous"
        RootfsDelete.delete(staged)
        RootfsDelete.delete(previous)
        try {
            RootArchive.import(input, staged)
            val looks = RootProcess.run("/system/bin/sh", "-c", "[ -d \"\$1/etc\" ] && [ -d \"\$1/usr\" ]", "sh", staged)
            check(looks.succeeded) { "that archive is not a container's filesystem (no /etc and /usr in it)" }
        } catch (e: Exception) {
            RootfsDelete.delete(staged)
            throw e
        }
        check(RootProcess.run("/system/bin/mv", rootfs, previous).succeeded) { "could not set the current filesystem aside" }
        if (!RootProcess.run("/system/bin/mv", staged, rootfs).succeeded) {
            RootProcess.run("/system/bin/mv", previous, rootfs)
            error("could not put the restored filesystem in place; the container is unchanged")
        }
        RootfsDelete.delete(previous)
    }

    override val cloneUnavailableReason: String? = null

    /**
     * A copy of a stopped container (docs/SPEC.md 3d "Copy"; Droidtop/tracker#758): its tree copied as root with
     * owners and modes kept, and its settings files (image, run settings, home and folders, sockets, devices). The copy
     * is always a sibling: a copy of the desktop's own gets a sibling's init and no provisioning plan. A copy that fails
     * part-way is deleted whole.
     */
    override suspend fun clone(container: Container, name: String): Container {
        val existing = listContainers()
        val source = existing.firstOrNull { it.container.id == container.id } ?: error("${container.id} no longer exists")
        check(!source.running) { "${source.displayName} is running: stop it to copy it" }
        val chosen = name.trim()
        ContainerNames.problemWith(chosen, existing.map { it.displayName })?.let { error(it) }
        val id = "droidtop-sibling-${UUID.randomUUID().toString().take(8)}"
        val rootfs = File(rootfsDir, id).absolutePath
        try {
            RootArchive.copy(source.container.rootfsPath, rootfs)
            if (container.role == ContainerRole.PRIMARY) writeInit(rootfs, null)
            withContext(Dispatchers.IO) {
                for (file in listOf(::imageRecordFile, ::runConfigFile, ::mountsFile, ::socketsFile, ::devicesFile, ::lookFile, ::networkFile)) {
                    file(container.id).takeIf { it.isFile }?.copyTo(file(id), overwrite = true)
                }
                writeConfig(id, rootfs)
                names.set(id, chosen)
            }
        } catch (e: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable) {
                RootfsDelete.delete(rootfs)
                for (file in listOf(::imageRecordFile, ::runConfigFile, ::mountsFile, ::socketsFile, ::devicesFile, ::lookFile, ::networkFile)) file(id).delete()
                File(configsDir, "$id.config").delete()
                File(configsDir, "$id.env").delete()
                names.remove(id)
            }
            throw e
        }
        return Container(id = id, role = ContainerRole.SIBLING, backend = backend, rootfsPath = rootfs)
    }

    override suspend fun destroy(container: Container) {
        // Best-effort stop -- the container may already be stopped, that's
        // not a reason to fail the whole destroy.
        RootProcess.run(binaryPath, "--name=${container.id}", "stop")

        File(configsDir, "${container.id}.config").delete()
        File(configsDir, "${container.id}.env").delete()
        imageRecordFile(container.id).delete()
        provisioningFile(container.id).delete()
        socketsFile(container.id).delete()
        lookFile(container.id).delete()
        networkFile(container.id).delete()
        mountsFile(container.id).delete()
        runConfigFile(container.id).delete()
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

    /**
     * For a sibling whose clipboard is off (docs/SPEC.md 3d "Clipboard per container"): droidtop's filtering socket bound
     * over the compositor's, and an empty file over sway's IPC, whose exec runs a program in the primary. Bound at
     * start, so the compositor must be up then; a container started before it gets no windows until its next start.
     */
    private fun clipboardBinds(name: String, sockets: ContainerSockets): List<Pair<String, String>> {
        if (sockets.clipboardShared || !sockets.waylandShared || name == PRIMARY_NAME) return emptyList()
        val wayland = ContainerLayout.findWaylandSocket(socketsDir) ?: return emptyList()
        val filter = ClipboardFilters.socketFor(name, context.filesDir, socketsDir)
        return listOf(filter.absolutePath to "${ContainerLayout.SOCKET_DIR}/${wayland.name}") +
            listOfNotNull(
                ContainerLayout.findSwayIpcSocket(socketsDir)?.let {
                    ClipboardFilters.blank(context.filesDir).absolutePath to "${ContainerLayout.SOCKET_DIR}/${it.name}"
                },
            )
    }

    private fun socketsFile(name: String) = File(configsDir, "$name.sockets")

    private fun readSockets(name: String): ContainerSockets {
        val lines = socketsFile(name).takeIf { it.isFile }?.readLines().orEmpty().associate { line ->
            val (key, value) = line.split("=", limit = 2).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
            key to value
        }
        return ContainerSockets(
            waylandShared = lines["wayland"]?.toBooleanStrictOrNull() ?: true,
            audioShared = lines["audio"]?.toBooleanStrictOrNull() ?: true,
            clipboardShared = lines["clipboard"]?.toBooleanStrictOrNull() ?: true,
        )
    }

    override suspend fun sockets(container: Container): ContainerSockets = withContext(Dispatchers.IO) { readSockets(container.id) }

    override suspend fun setSockets(container: Container, sockets: ContainerSockets) = withContext(Dispatchers.IO) {
        socketsFile(container.id).apply { parentFile?.mkdirs() }
            .writeText("wayland=${sockets.waylandShared}\naudio=${sockets.audioShared}\nclipboard=${sockets.clipboardShared}\n")
        if (sockets.clipboardShared) ClipboardFilters.stop(container.id)
        Unit
    }

    // ---- network (docs/SPEC.md 3d "Network") ----

    private fun networkFile(name: String) = File(configsDir, "$name.network")

    /** Shared unless the file says otherwise. */
    private fun readNetworkShared(name: String): Boolean =
        networkFile(name).takeIf { it.isFile }?.readText()?.trim()?.removePrefix("shared=")?.toBooleanStrictOrNull() ?: true

    override val networkUnavailableReason: String? = null

    override suspend fun networkShared(container: Container): Boolean = withContext(Dispatchers.IO) { readNetworkShared(container.id) }

    override suspend fun setNetworkShared(container: Container, shared: Boolean) = withContext(Dispatchers.IO) {
        networkFile(container.id).apply { parentFile?.mkdirs() }.writeText("shared=$shared\n")
        Unit
    }

    // ---- scale and theme (docs/SPEC.md 3d "Scale and theme") ----

    private fun lookFile(name: String) = File(configsDir, "$name.look")

    private fun readLook(name: String): ContainerLook =
        ContainerLook.decode(lookFile(name).takeIf { it.isFile }?.readText().orEmpty())

    /**
     * Each container has its own process space, and the compositor runs in the desktop's own: a window from another
     * container reaches it with no process it can see, so which container it came from is unknown (docs/SPEC.md 3d
     * "Keyboard").
     */
    override val windowOwnerUnavailableReason: String =
        "Each container has its own processes here, so droidtop can't tell which one a window is from"

    override suspend fun look(container: Container): ContainerLook = withContext(Dispatchers.IO) { readLook(container.id) }

    override suspend fun setLook(container: Container, look: ContainerLook) = withContext(Dispatchers.IO) {
        lookFile(container.id).apply { parentFile?.mkdirs() }.writeText(look.encode())
        Unit
    }

    // Real: droidspaces bridges Android's audio HAL to a host PulseAudio
    // daemon and binds it into any container with enable_pulseaudio=1 --
    // see this class's own doc comment and [writeConfig].
    override val audioSharingUnavailableReason: String? = null

    // ---- home and folders (docs/SPEC.md 3d "Home and folders") ----

    private fun mountsFile(name: String) = File(configsDir, "$name.mounts")

    /** How the image says its own program runs, kept from creation (docs/SPEC.md 3, "The image's own program"). */
    private fun runConfigFile(name: String) = File(configsDir, "$name.imageconfig")

    override suspend fun imageRunConfig(container: Container): ImageRunConfig? = withContext(Dispatchers.IO) {
        runConfigFile(container.id).takeIf { it.isFile }?.let { ImageRunConfig.fromImageConfig(it.readText()) }
    }

    private fun readMounts(name: String): EnvironmentMounts =
        EnvironmentMounts.decode(mountsFile(name).takeIf { it.isFile }?.readText().orEmpty())

    private fun writeMounts(name: String, mounts: EnvironmentMounts) {
        mountsFile(name).apply { parentFile?.mkdirs() }.writeText(EnvironmentMounts.encode(mounts))
    }

    override suspend fun mounts(container: Container): EnvironmentMounts = withContext(Dispatchers.IO) { readMounts(container.id) }

    override suspend fun setMounts(container: Container, mounts: EnvironmentMounts) = withContext(Dispatchers.IO) {
        writeMounts(container.id, mounts)
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
        private const val NO_ROOT_SHELL = "Reading this container's use needs Shizuku or Sui, which droidtop can't reach right now"
    }
}
