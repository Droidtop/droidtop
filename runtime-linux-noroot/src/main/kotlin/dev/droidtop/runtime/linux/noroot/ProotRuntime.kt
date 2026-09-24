package dev.droidtop.runtime.linux.noroot

import android.content.Context
import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerBackend
import dev.droidtop.runtime.ContainerExecResult
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerLayout
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.CraneRootfsPuller
import dev.droidtop.runtime.ImageCache
import dev.droidtop.runtime.ImageCachePolicy
import dev.droidtop.runtime.PrimaryProvisioning
import dev.droidtop.runtime.RootfsImage
import dev.droidtop.runtime.SharedVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * No-root Linux container backend: every container process runs under
 * proot (ptrace-based path translation and fake root) instead of real
 * kernel namespaces, trading isolation and some speed for needing nothing
 * from the device (docs/SPEC.md §3).
 *
 * The proot is vendor/proot, Termux's build, packaged by
 * build-scripts/build-vendor-deps.sh as `libproot.so` plus its loaders in
 * this module's jniLibs, and run from `nativeLibraryDir` (an unrooted app
 * may not exec a file it extracted itself above targetSdk 28). It is the
 * same binary gamenative's `DefaultProotContainerBackend` looks for under
 * that name, and the same environment variables drive it.
 *
 * Same container model as the root backend: a PRIMARY that boots
 * [ContainerLayout.primaryInitScript] (provision once, then the compositor,
 * headless) and siblings that share its socket directory and the device's
 * shared storage ([ContainerLayout.SHARED_STORAGE_DIR]). Each host
 * directory in [ContainerLayout] is a proot bind, so the compositor's
 * socket is a real Unix socket file under [socketsDir] on the Android side,
 * which `:host-bridge` connects to directly.
 *
 * Where the root backend has a running container, this one has processes:
 * a sibling has no init under proot, so "starting" one is a no-op and each
 * [exec] is its own proot session. The PRIMARY's boot script is one
 * long-lived proot process, tracked in [running] (shared by every instance
 * in the app process, so the container manager and the desktop session
 * see the same one).
 *
 * Diagnostics go to logcat under [TAG] and to
 * `<external files>/logs/desktop-container.log`, which is readable on an
 * unrooted device without run-as.
 */
class ProotRuntime(
    private val context: Context,
    private val imageCache: ImageCache,
    private val cachePolicy: ImageCachePolicy,
) : ContainerRuntime {
    override val backend: ContainerBackend = ContainerBackend.PROOT

    private val baseDir = File(context.filesDir, "proot")
    private val containersDir = File(baseDir, "containers")

    /** Host side of [ContainerLayout.SOCKET_DIR], shared by every container. */
    private val socketsDir = File(baseDir, "sockets")

    /** proot's own scratch space (PROOT_TMP_DIR). Kept apart from gamenative's `files/tmp`, which its XEnvironment empties on every start. */
    private val prootTmpDir = File(baseDir, "tmp")

    /** Host side of the guest's /dev/shm: Android has no /dev/shm an app may write to. */
    private val shmDir = File(baseDir, "shm")

    /** Host side of the generated /etc/resolv.conf and /etc/hosts (see [writeNetworkFiles]). */
    private val etcDir = File(baseDir, "etc")

    private val appStorageDir: File = context.filesDir
    private val nativeLibraryDir: String = context.applicationInfo.nativeLibraryDir
    private val rootfsPuller = CraneRootfsPuller(context, ProotRootfsUnpacker(containersDir))
    private val log = ContainerLog(context)

    override suspend fun createPrimary(image: RootfsImage, provisioning: PrimaryProvisioning): Container =
        createContainer(PRIMARY_NAME, ContainerRole.PRIMARY, image, provisioning)

    override suspend fun createSibling(image: RootfsImage): Container =
        createContainer("droidtop-sibling-${UUID.randomUUID().toString().take(8)}", ContainerRole.SIBLING, image, null)

    private suspend fun createContainer(
        name: String,
        role: ContainerRole,
        image: RootfsImage,
        provisioning: PrimaryProvisioning?,
    ): Container {
        stopProcess(name)
        val rootfs = rootfsOf(name)
        log.line("creating $name from ${image.reference}${image.digest?.let { "@$it" } ?: ""}")
        rootfsPuller.pullAndUnpack(image, rootfs.absolutePath, imageCache, cachePolicy)
        withContext(Dispatchers.IO) {
            val config = Properties()
            config[KEY_ROLE] = role.name
            config[KEY_IMAGE] = image.reference
            configOf(name).outputStream().use { config.store(it, "droidtop proot container") }
        }
        provisioning?.let { recordProvisioning(name, it) }
        log.line("created $name")
        return Container(id = name, role = role, backend = backend, rootfsPath = rootfs.absolutePath)
    }

    /**
     * Boots the PRIMARY: its boot script runs as one proot session, and
     * this returns once the compositor's socket accepts a connection. It
     * fails when the script exits first (a failed install, a compositor
     * that died), quoting the script's last output, and when the script
     * has printed nothing for [STALL_TIMEOUT_MS] without the socket
     * appearing. A first boot provisions the desktop first; that is many
     * minutes of package installation, reported through [onProgress].
     */
    override suspend fun start(container: Container, provisioning: PrimaryProvisioning?, onProgress: (String) -> Unit) {
        requireRootfs(container)
        if (container.role != ContainerRole.PRIMARY) return
        stopProcess(container.id)

        if (provisioning != null) recordProvisioning(container.id, provisioning)
        val plan = readProvisioning(container.id)
            ?: error("${container.id} has no provisioning plan recorded; recreate it")
        withContext(Dispatchers.IO) {
            prepareSharedDirectories()
            // A socket (and its .lock) left by a previous session would
            // look like a live compositor to the wait below.
            socketsDir.listFiles()?.forEach { if (!it.isDirectory) it.delete() }
            writeNetworkFiles()
        }

        val script = ContainerLayout.primaryInitScript(plan)
        log.line("starting ${container.id}: ${plan.compositorCommand}")
        val process = withContext(Dispatchers.IO) {
            startSession(rootfsOf(container.id), listOf("/bin/sh", "-c", script), emptyMap(), mergeStderr = true)
        }
        running[container.id] = process
        val tail = OutputTail()
        pump(process.inputStream, "${container.id}") { tail.add(it) }

        var lastReported: String? = null
        while (true) {
            if (!process.isAlive) {
                running.remove(container.id, process)
                val code = process.exitValue()
                error(
                    "the primary container's boot script exited with code $code before the compositor " +
                        "came up. Last output:\n" + tail.lines().joinToString("\n"),
                )
            }
            val socket = ContainerLayout.findWaylandSocket(socketsDir)
            if (socket != null && canConnect(socket)) {
                log.line("${container.id}: compositor socket is accepting connections at ${socket.path}")
                return
            }
            val latest = tail.latest()
            if (latest != null && latest != lastReported) {
                lastReported = latest
                onProgress(latest)
            }
            if (System.currentTimeMillis() - tail.lastLineAt() > STALL_TIMEOUT_MS) {
                stopProcess(container.id)
                error(
                    "the primary container printed nothing for ${STALL_TIMEOUT_MS / 60_000} minutes and its " +
                        "compositor never appeared. Last output:\n" + tail.lines().joinToString("\n"),
                )
            }
            delay(POLL_MS)
        }
    }

    override suspend fun stop(container: Container) {
        stopProcess(container.id)
    }

    override suspend fun destroy(container: Container) {
        stopProcess(container.id)
        withContext(Dispatchers.IO) { TreeDelete.delete(containerDir(container.id), containersDir) }
        log.line("destroyed ${container.id}")
    }

    /**
     * The persisted per-container configs ARE the set of known containers
     * (every create writes one, destroy removes the whole directory);
     * "running" means a live boot process, which only a started PRIMARY has.
     */
    override suspend fun listContainers(): List<ContainerInfo> = withContext(Dispatchers.IO) {
        containersDir.listFiles().orEmpty()
            .filter { configOf(it.name).isFile }
            .map { dir ->
                val role = runCatching { ContainerRole.valueOf(readConfig(dir.name).getProperty(KEY_ROLE)) }
                    .getOrDefault(ContainerRole.SIBLING)
                ContainerInfo(
                    container = Container(dir.name, role, backend, rootfsOf(dir.name).absolutePath),
                    running = running[dir.name]?.isAlive == true,
                )
            }
    }

    /**
     * Runs [command] as its own proot session in [container]'s rootfs and
     * waits for it: a GUI program started this way returns when its window
     * closes, as [dev.droidtop.runtime.ContainerTerminal] expects. Output
     * is captured (the tail of each stream, if a program is chatty) and also
     * logged line by line. Cancelling the caller kills the session.
     */
    override suspend fun exec(container: Container, command: List<String>, env: Map<String, String>): ContainerExecResult {
        requireRootfs(container)
        withContext(Dispatchers.IO) {
            prepareSharedDirectories()
            writeNetworkFiles()
        }
        val name = command.firstOrNull()?.substringAfterLast('/') ?: "exec"
        log.line("exec in ${container.id}: ${command.joinToString(" ")}")
        val process = withContext(Dispatchers.IO) { startSession(rootfsOf(container.id), command, env, mergeStderr = false) }
        val stdout = OutputTail(maxLines = CAPTURE_LINES)
        val stderr = OutputTail(maxLines = CAPTURE_LINES)
        val outThread = pump(process.inputStream, "${container.id}/$name") { stdout.add(it) }
        val errThread = pump(process.errorStream, "${container.id}/$name!") { stderr.add(it) }
        val exitCode = try {
            runInterruptible(Dispatchers.IO) { process.waitFor() }
        } catch (t: Throwable) {
            process.destroyForcibly()
            throw t
        }
        runInterruptible(Dispatchers.IO) {
            outThread.join(PUMP_JOIN_MS)
            errThread.join(PUMP_JOIN_MS)
        }
        log.line("exec in ${container.id}: $name exited with $exitCode")
        return ContainerExecResult(
            exitCode = exitCode,
            stdout = stdout.lines().joinToString("\n"),
            stderr = stderr.lines().joinToString("\n"),
        )
    }

    /**
     * Runs a trivial command through proot against the device's own root:
     * proves ptrace is allowed here and that the packaged loader can start
     * a program, without needing any image.
     */
    override suspend fun checkSystemRequirements(): ContainerExecResult = withContext(Dispatchers.IO) {
        installedAbiMismatch()?.let { return@withContext ContainerExecResult(126, "", it) }
        val proot = File(nativeLibraryDir, PROOT)
        if (!proot.isFile) {
            return@withContext ContainerExecResult(127, "", "proot is not packaged for this device's ABI (${proot.path} is missing)")
        }
        prepareSharedDirectories()
        val builder = ProcessBuilder(
            proot.absolutePath, "--kill-on-exit", "--rootfs=/", "/system/bin/sh", "-c", "echo $CHECK_TOKEN",
        ).directory(baseDir)
        builder.environment().putAll(prootEnvironment())
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return@withContext ContainerExecResult(126, "", "could not start proot: ${e.message}")
        }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(CHECK_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val code = if (finished) process.exitValue() else 124
        val ok = code == 0 && out.contains(CHECK_TOKEN)
        log.line("proot check: exit $code, ${if (ok) "ok" else "failed: ${err.trim()}"}")
        ContainerExecResult(if (ok) 0 else maxOf(code, 1), out, err)
    }

    /**
     * Why the packaged proot cannot run here, when the reason is the ABI
     * droidtop was installed as rather than anything proot does.
     *
     * An app's native libraries are those of ONE ABI, chosen by the
     * package manager at install time, and proot and its loaders are
     * executables: they only run when that ABI is the kernel's own.
     * Android-x86 derivatives with ARM translation (BlueStacks is one:
     * abilist x86_64,x86,arm64-v8a,...) install an APK as arm64-v8a when
     * its arm64 library set is the fuller one, which droidtop's is
     * (gamenative's prebuilt natives exist for arm64 only). Translation
     * covers libraries loaded into the app, not programs it executes, so
     * exec'ing the ARM proot on that x86_64 kernel falls through to
     * `/system/bin/sh` reading the ELF as a script (rig, dq-desktop-01:
     * "libproot.so[1]: syntax error: unexpected '('"). Said as what it is,
     * with the remedy that works: the per-ABI APK (app/build.gradle.kts
     * `splits`). Forcing the ABI at install (`pm install --abi`) crashed
     * the rig's package installer (dq-desktop-03).
     */
    private fun installedAbiMismatch(): String? {
        val installedAbi = when (File(nativeLibraryDir).name) {
            "arm64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            else -> File(nativeLibraryDir).name
        }
        val deviceAbi = android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: return null
        if (installedAbi == deviceAbi) return null
        return "droidtop is installed as $installedAbi, but this device's own ABI is $deviceAbi: Android " +
            "chose to run droidtop's native code through ARM translation, which cannot start the Linux " +
            "container tools. Install droidtop's $deviceAbi-only APK instead of the universal one."
    }

    override fun primaryWaylandSocketPath(): String =
        (ContainerLayout.findWaylandSocket(socketsDir) ?: error("the primary compositor has no socket in ${socketsDir.path}"))
            .absolutePath

    override fun hostStorageToContainerPath(hostPath: File): String =
        ContainerLayout.hostStorageToContainerPath(appStorageDir, hostPath)

    // ---- process plumbing ----

    /**
     * One proot session: [guestCommand] inside [rootfs] as fake root, with
     * [ContainerLayout]'s directories bound in and a clean environment
     * ([baseGuestEnvironment], the client environment, then [env]).
     *
     *  - `--kill-on-exit`: ending the session ends everything it started.
     *  - `--root-id`: uid/gid 0 as far as the guest can tell; a distro's
     *    package manager refuses to run otherwise.
     *  - `--link2symlink`: hard links the guest makes become proot-managed
     *    symlinks (dpkg makes them; Android refuses an app most of them).
     *  - `--sysvipc`: System V IPC emulated in proot; Android's kernel
     *    build does not offer it to apps.
     *  - `--ashmem-memfd`: memfd_create served from ashmem where the
     *    device's seccomp policy predates it (Android 9 is one); wlroots,
     *    Wayland clients and libwayland itself allocate shared memory
     *    with memfd. proot probes and only steps in when memfd is refused.
     */
    private fun startSession(rootfs: File, guestCommand: List<String>, env: Map<String, String>, mergeStderr: Boolean): Process {
        val guestEnvironment = LinkedHashMap<String, String>().apply {
            putAll(baseGuestEnvironment)
            putAll(ContainerLayout.clientEnvironment(ContainerLayout.findWaylandSocket(socketsDir)?.name))
            putAll(env)
        }
        val argv = buildList {
            add(File(nativeLibraryDir, PROOT).absolutePath)
            add("--kill-on-exit")
            add("--root-id")
            add("--link2symlink")
            add("--sysvipc")
            add("--ashmem-memfd")
            add("--rootfs=${rootfs.absolutePath}")
            add("--bind=/dev")
            add("--bind=/proc")
            add("--bind=/sys")
            add("--bind=${shmDir.absolutePath}:/dev/shm")
            add("--bind=${socketsDir.absolutePath}:${ContainerLayout.SOCKET_DIR}")
            add("--bind=${appStorageDir.absolutePath}:${ContainerLayout.APP_STORAGE_DIR}")
            // Volumes mounted now, looked up per session: a card or USB
            // drive mounted since the last one is there the next time.
            ContainerLayout.sharedStorageBinds(SharedVolume.mounted(context)).forEach { (host, guest) -> add("--bind=$host:$guest") }
            add("--bind=${File(etcDir, "resolv.conf").absolutePath}:/etc/resolv.conf")
            add("--bind=${File(etcDir, "hosts").absolutePath}:/etc/hosts")
            add("--cwd=/root")
            add("/usr/bin/env")
            add("-i")
            guestEnvironment.forEach { (key, value) -> add("$key=$value") }
            addAll(guestCommand)
        }
        val builder = ProcessBuilder(argv).directory(baseDir).redirectErrorStream(mergeStderr)
        builder.environment().putAll(prootEnvironment())
        return builder.start()
    }

    private fun prootEnvironment(): Map<String, String> = mapOf(
        "PROOT_TMP_DIR" to prootTmpDir.absolutePath,
        "PROOT_LOADER" to File(nativeLibraryDir, PROOT_LOADER).absolutePath,
        "PROOT_LOADER_32" to File(nativeLibraryDir, PROOT_LOADER_32).absolutePath,
    )

    /** Reads [input] line by line on its own thread, logging each line and handing it to [sink]. */
    private fun pump(input: InputStream, label: String, sink: (String) -> Unit): Thread =
        thread(name = "proot-$label", isDaemon = true) {
            try {
                input.bufferedReader().forEachLine { line ->
                    sink(line)
                    log.line("[$label] $line")
                }
            } catch (_: IOException) {
                // The process went away; the stream closing is the normal end.
            }
        }

    private suspend fun stopProcess(name: String) {
        val process = running.remove(name) ?: return
        withContext(Dispatchers.IO) {
            process.destroy()
            if (!process.waitFor(STOP_GRACE_S, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(STOP_GRACE_S, TimeUnit.SECONDS)
            }
        }
        log.line("stopped $name")
    }

    private fun canConnect(socket: File): Boolean = try {
        LocalSocket().use { it.connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)) }
        true
    } catch (_: IOException) {
        false
    }

    // ---- files ----

    private fun containerDir(name: String) = File(containersDir, name)
    private fun rootfsOf(name: String) = File(containerDir(name), "rootfs")
    private fun configOf(name: String) = File(containerDir(name), "container.properties")

    private fun readConfig(name: String): Properties =
        Properties().apply { configOf(name).inputStream().use { load(it) } }

    private suspend fun recordProvisioning(name: String, provisioning: PrimaryProvisioning) = withContext(Dispatchers.IO) {
        val config = readConfig(name)
        config[KEY_INSTALL] = provisioning.installCommand
        config[KEY_COMPOSITOR] = provisioning.compositorCommand
        configOf(name).outputStream().use { config.store(it, "droidtop proot container") }
    }

    private suspend fun readProvisioning(name: String): PrimaryProvisioning? = withContext(Dispatchers.IO) {
        val config = readConfig(name)
        val install = config.getProperty(KEY_INSTALL) ?: return@withContext null
        val compositor = config.getProperty(KEY_COMPOSITOR) ?: return@withContext null
        PrimaryProvisioning(install, compositor)
    }

    private fun requireRootfs(container: Container) {
        check(rootfsOf(container.id).isDirectory) { "${container.id} has no rootfs at ${rootfsOf(container.id).path}" }
    }

    private fun prepareSharedDirectories() {
        for (dir in listOf(socketsDir, prootTmpDir, shmDir, etcDir)) dir.mkdirs()
        // XDG_RUNTIME_DIR must be private to its user; Wayland libraries
        // warn about anything else.
        Files.setPosixFilePermissions(socketsDir.toPath(), PosixFilePermissions.fromString("rwx------"))
    }

    /**
     * The guest's /etc/resolv.conf and /etc/hosts, bound over whatever the
     * image has, as a container engine does: a stock image ships no DNS
     * configuration of its own, and Android has no /etc/resolv.conf for it
     * to inherit. The nameservers are the active network's own, read from
     * Android at every session start, so the guest resolves exactly as the
     * device does (none are invented when Android reports none).
     */
    private fun writeNetworkFiles() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val servers = connectivity?.activeNetwork
            ?.let { connectivity.getLinkProperties(it) }
            ?.dnsServers
            .orEmpty()
            .mapNotNull { it.hostAddress }
        if (servers.isEmpty()) log.line("no DNS servers reported by Android for the active network")
        File(etcDir, "resolv.conf").writeText(
            "# Written by droidtop from Android's active network at session start.\n" +
                servers.joinToString("") { "nameserver $it\n" },
        )
        File(etcDir, "hosts").writeText(
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n",
        )
    }

    /** The last [maxLines] lines a process printed, and when it last printed anything. */
    private class OutputTail(private val maxLines: Int = PROGRESS_LINES) {
        private val buffer = ArrayDeque<String>()
        @Volatile private var lastAt = System.currentTimeMillis()

        @Synchronized fun add(line: String) {
            buffer.addLast(line)
            while (buffer.size > maxLines) buffer.removeFirst()
            lastAt = System.currentTimeMillis()
        }

        @Synchronized fun lines(): List<String> = buffer.toList()
        @Synchronized fun latest(): String? = buffer.lastOrNull { it.isNotBlank() }
        fun lastLineAt(): Long = lastAt
    }

    /**
     * Appends to `<external files>/logs/desktop-container.log`. Started fresh
     * when it passes [MAX_LOG_BYTES], so a long-lived install never grows it
     * without bound.
     */
    private class ContainerLog(context: Context) {
        private val file: File? = context.getExternalFilesDir("logs")?.let { File(it, "desktop-container.log") }
        private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        @Synchronized fun line(text: String) {
            Log.i(TAG, text)
            val target = file ?: return
            runCatching {
                if (target.length() > MAX_LOG_BYTES) target.writeText("")
                target.appendText("${stamp.format(Date())} $text\n")
            }
        }
    }

    companion object {
        private const val TAG = "droidtop.proot"
        private const val PRIMARY_NAME = "droidtop-primary"

        private const val PROOT = "libproot.so"
        private const val PROOT_LOADER = "libproot-loader.so"
        private const val PROOT_LOADER_32 = "libproot-loader32.so"

        private const val KEY_ROLE = "role"
        private const val KEY_IMAGE = "image"
        private const val KEY_INSTALL = "provision.install"
        private const val KEY_COMPOSITOR = "provision.compositor"

        private const val CHECK_TOKEN = "droidtop-proot-ok"
        private const val CHECK_TIMEOUT_S = 30L
        private const val POLL_MS = 500L
        private const val STALL_TIMEOUT_MS = 15L * 60L * 1000L
        private const val STOP_GRACE_S = 5L
        private const val PUMP_JOIN_MS = 2_000L
        private const val PROGRESS_LINES = 40
        private const val CAPTURE_LINES = 2_000
        private const val MAX_LOG_BYTES = 4L * 1024L * 1024L

        /**
         * Every guest process's starting environment. `env -i` means
         * nothing of Android's own environment (its PATH, its
         * ANDROID_* variables) leaks into a Linux program.
         */
        private val baseGuestEnvironment = mapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
        )

        /** The PRIMARY's boot process, by container id, shared by every instance in this app process. */
        private val running = ConcurrentHashMap<String, Process>()
    }
}
