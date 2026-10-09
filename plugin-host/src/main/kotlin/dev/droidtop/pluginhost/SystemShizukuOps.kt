package dev.droidtop.pluginhost

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.droidtop.runtime.tasks.APPOP_MODES
import dev.droidtop.runtime.tasks.APPOP_NAME
import dev.droidtop.runtime.tasks.BackendState
import dev.droidtop.runtime.tasks.ElevatedBackend
import dev.droidtop.runtime.tasks.ElevatedFiles
import dev.droidtop.runtime.tasks.ForceStopResult
import dev.droidtop.runtime.tasks.ShellOutput
import dev.droidtop.runtime.tasks.TaskPrivileges
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.shizuku.api.BinderContainer
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import rikka.sui.Sui

/**
 * The system Shizuku as a [PrivilegedShell][dev.droidtop.runtime.tasks.PrivilegedShell] backend: the official Shizuku
 * app, or Sui (the Magisk module, which exposes the same API), reached through the Shizuku API in droidtop's own
 * process. It is the other backend beside [PluginPrivilegedOps]; the user picks (docs/SPEC.md "The task manager").
 * droidtop calls no `su` and holds no root: the commands run in Shizuku's server, as the ADB shell user or as root
 * where Shizuku itself was started with root.
 *
 * Capabilities: force-stop, the system task list and any other command through [exec] (appops, `svc` and `cmd`
 * radios, package install and uninstall), and [grantPermission]. Calls block on Shizuku's server: callers run them
 * off the main thread.
 */
class SystemShizukuOps : ElevatedBackend {
    override fun state(): BackendState {
        ShizukuTransport.ensureStarted()
        val alive = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
        if (!alive) return BackendState.ABSENT
        val allowed = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return if (allowed) BackendState.READY else BackendState.NEEDS_PERMISSION
    }

    override fun capabilities(): TaskPrivileges =
        if (state() == BackendState.READY) {
            TaskPrivileges(forceStop = true, shell = true, grantPermission = true, appOps = true, files = true)
        } else {
            TaskPrivileges.NONE
        }

    override fun available(): TaskPrivileges = capabilities()

    /**
     * Waits up to [timeoutMs] for Shizuku's binder when this process has none yet. The binder reaches the main process
     * only by asking the `:pluginhost` provider or by its broadcast ([ShizukuTransport]), and both start on the first
     * [state] call, which then answers ABSENT at once: a Kill that was the first privileged call fell back to the
     * unconfirmable background kill. Asks the provider again first, for a binder the broadcast missed.
     */
    override fun connect(timeoutMs: Long) {
        ShizukuTransport.ensureStarted()
        if (binderAlive()) return
        ShizukuTransport.askProvider()
        // Only the Shizuku app delivers a binder later; without it installed there is nothing to wait for (Sui answers
        // at once, through ensureStarted).
        if (binderAlive() || !ShizukuTransport.shizukuAppInstalled()) return
        val arrived = CountDownLatch(1)
        val listener = Shizuku.OnBinderReceivedListener { arrived.countDown() }
        runCatching { Shizuku.addBinderReceivedListenerSticky(listener) }
        try {
            arrived.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { Shizuku.removeBinderReceivedListener(listener) }
        }
    }

    private fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    override fun forceStop(packageName: String): ForceStopResult {
        if (!PACKAGE_NAME.matches(packageName)) return ForceStopResult.Failed("not a package name")
        notReady()?.let { return ForceStopResult.Failed(it) }
        val out = run(listOf("am", "force-stop", packageName), STOP_TIMEOUT_MS) ?: return ForceStopResult.Failed("Shizuku did not answer in time")
        return if (out.exit == 0) ForceStopResult.Stopped else ForceStopResult.Failed(out.stderr.ifBlank { "am force-stop exited with ${out.exit}" })
    }

    override fun exec(argv: List<String>): ShellOutput? {
        if (argv.isEmpty() || argv.size > MAX_ARGS || argv.any { it.isEmpty() || it.length > MAX_ARG_LENGTH }) return null
        if (notReady() != null) return null
        return run(argv, EXEC_TIMEOUT_MS)
    }

    /**
     * A long-lived process in Shizuku's server, for the rooted desktop stack
     * (dev.droidtop.runtime.RootProcess): no timeout and no output cap, the
     * caller owns its streams and its lifetime. Bounded only in size, so a
     * shell script for a container still fits.
     */
    override fun spawn(argv: List<String>): Process? {
        if (argv.isEmpty() || argv.size > MAX_ARGS || argv.sumOf { it.length } > MAX_SPAWN_CHARS || argv.any { it.isEmpty() }) return null
        if (notReady() != null) return null
        return newProcess(argv)
    }

    override fun grantPermission(packageName: String, permission: String): Boolean {
        if (!PACKAGE_NAME.matches(packageName) || !PERMISSION_NAME.matches(permission)) return false
        if (notReady() != null) return false
        return run(listOf("pm", "grant", packageName, permission), STOP_TIMEOUT_MS)?.exit == 0
    }

    override fun setAppOp(packageName: String, op: String, mode: String): Boolean {
        if (!PACKAGE_NAME.matches(packageName) || !op.matches(APPOP_NAME) || mode !in APPOP_MODES) return false
        if (notReady() != null) return false
        return run(listOf("appops", "set", packageName, op, mode), STOP_TIMEOUT_MS)?.exit == 0
    }

    /** `cat` as Shizuku's user; the bytes as they are, refused past [ElevatedFiles.MAX_READ_BYTES]. */
    override fun readFile(path: String): ByteArray? {
        if (!ElevatedFiles.allowed(path) || notReady() != null) return null
        val process = newProcess(listOf("cat", path)) ?: return null
        val err = Capture(process.errorStream).also { it.start() }
        return try {
            val bytes = process.inputStream.use { input -> input.readNBytesCompat(ElevatedFiles.MAX_READ_BYTES + 1) }
            if (!process.waitFor(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            err.join(STREAM_JOIN_MS)
            bytes.takeIf { process.exitValue() == 0 && it.size <= ElevatedFiles.MAX_READ_BYTES }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { process.destroy() }
        }
    }

    /**
     * Writes through `dd` beside the target and renames it over the target only once it is whole, so an emulator
     * never reads a half-written config or BIOS file.
     */
    override fun writeFile(path: String, data: ByteArray): Boolean {
        if (!ElevatedFiles.allowed(path) || data.size > ElevatedFiles.MAX_WRITE_BYTES || notReady() != null) return false
        if (run(listOf("mkdir", "-p", path.substringBeforeLast('/')), STOP_TIMEOUT_MS)?.exit != 0) return false
        val staged = "$path.droidtop-part"
        val process = newProcess(listOf("dd", "of=$staged")) ?: return false
        val out = Capture(process.inputStream).also { it.start() }
        val err = Capture(process.errorStream).also { it.start() }
        val written = try {
            process.outputStream.use { it.write(data) }
            process.waitFor(FILE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && process.exitValue() == 0
        } catch (t: Throwable) {
            false
        } finally {
            out.join(STREAM_JOIN_MS)
            err.join(STREAM_JOIN_MS)
            runCatching { process.destroy() }
        }
        if (!written || run(listOf("mv", "-f", staged, path), STOP_TIMEOUT_MS)?.exit != 0) {
            run(listOf("rm", "-f", staged), STOP_TIMEOUT_MS)
            return false
        }
        return true
    }

    /**
     * Asks Shizuku to show its own dialog allowing droidtop. False when there is no Shizuku to ask. The answer is
     * not awaited: [state] turns READY once the user has said yes.
     */
    fun requestPermission(): Boolean =
        runCatching {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
                true
            }
        }.getOrDefault(false)

    /** Null when Shizuku can run a command now, otherwise a short reason. */
    private fun notReady(): String? = when (state()) {
        BackendState.READY -> null
        BackendState.NEEDS_PERMISSION -> "droidtop is not allowed in Shizuku"
        BackendState.ABSENT -> "Shizuku is not running"
    }

    /**
     * Runs [argv] in Shizuku's server directly (never through a shell). Null when it does not finish within
     * [timeoutMs], or Shizuku cannot start it. `Shizuku.newProcess` is private since Shizuku 13, so it is reached by
     * reflection, as the Shizuku provider plugin does; a user service would add a second process for the same effect.
     */
    private fun run(argv: List<String>, timeoutMs: Long): ShellOutput? {
        val process = newProcess(argv) ?: return null
        val out = Capture(process.inputStream)
        val err = Capture(process.errorStream)
        out.start()
        err.start()
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                null
            } else {
                out.join(STREAM_JOIN_MS)
                err.join(STREAM_JOIN_MS)
                ShellOutput(process.exitValue(), out.text(), err.text())
            }
        } finally {
            runCatching { process.destroy() }
        }
    }

    /** [argv] started in Shizuku's server, or null when Shizuku cannot start it. */
    private fun newProcess(argv: List<String>): Process? = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(null, argv.toTypedArray(), null, null) as Process
    } catch (t: Throwable) {
        null
    }

    /** Up to [limit] bytes of the stream (InputStream.readNBytes is API 33). */
    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Reads one stream on its own thread so a full pipe never stalls the command, keeping the first [MAX_STREAM_CHARS] characters. */
    private class Capture(private val stream: InputStream) : Thread() {
        private val text = StringBuilder()

        override fun run() {
            val buffer = CharArray(4096)
            runCatching {
                stream.bufferedReader().use { reader ->
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        synchronized(text) { if (text.length < MAX_STREAM_CHARS) text.append(buffer, 0, minOf(n, MAX_STREAM_CHARS - text.length)) }
                    }
                }
            }
        }

        fun text(): String = synchronized(text) { text.toString().trim() }
    }

    companion object {
        private const val REQUEST_CODE = 1
        private const val MAX_ARGS = 64
        private const val MAX_ARG_LENGTH = 4096
        private const val MAX_SPAWN_CHARS = 256 * 1024
        private const val MAX_STREAM_CHARS = 1024 * 1024
        private const val STREAM_JOIN_MS = 500L
        private const val STOP_TIMEOUT_MS = 8_000L
        private const val EXEC_TIMEOUT_MS = 15_000L
        private const val FILE_TIMEOUT_MS = 60_000L
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+\$")
        private val PERMISSION_NAME = Regex("^[A-Za-z][A-Za-z0-9_.]*\$")
    }
}

/**
 * How the system Shizuku's binder reaches droidtop's main process. Shizuku's server pushes it to the one provider
 * named `<applicationId>.shizuku`, which lives in `:pluginhost` (plugin-host's manifest, docs/plugin-api.md 2.7).
 * Shizuku's built-in multi-process support then shares it: the provider process rebroadcasts the binder, and any
 * other process asks the provider for it. Sui needs no provider: it hands the binder to any process that asks, so
 * each process asks once. Everything here is idempotent and safe to call from any process.
 */
object ShizukuTransport {
    private const val PROVIDER_PROCESS_SUFFIX = ":pluginhost"

    @Volatile
    private var started = false

    /**
     * Declares which process this is, before any binder can arrive, and keeps the application context the binder
     * request needs. Cheap and with no IPC: call it from `Application.onCreate` in every process. The provider
     * process must know it is one, or it never rebroadcasts the binder.
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else legacyProcessName()
        providerProcess = name?.endsWith(PROVIDER_PROCESS_SUFFIX) == true
        ShizukuProvider.enableMultiProcessSupport(providerProcess)
    }

    @Volatile
    private var providerProcess = false

    /**
     * Asks the `:pluginhost` provider for the binder it holds, as [ShizukuProvider.requestBinderForNonProviderProcess]
     * does, but without registering another broadcast receiver each time. For a process whose one request at start
     * found the provider empty and whose broadcast never came. Blocks on the provider: background only.
     */
    fun askProvider() {
        val context = appContext ?: return
        if (providerProcess) return
        runCatching {
            val reply = context.contentResolver.call(
                Uri.parse("content://${context.packageName}.shizuku"),
                ShizukuProvider.METHOD_GET_BINDER,
                null,
                Bundle(),
            ) ?: return
            reply.classLoader = BinderContainer::class.java.classLoader
            @Suppress("DEPRECATION")
            val container = reply.getParcelable<BinderContainer>(EXTRA_BINDER)
            container?.binder?.let { Shizuku.onBinderReceived(it, context.packageName) }
        }
    }

    /** Whether the Shizuku app itself is installed (ShizukuProvider.MANAGER_APPLICATION_ID). A PackageManager read: background only. */
    fun shizukuAppInstalled(): Boolean {
        val context = appContext ?: return false
        return runCatching { context.packageManager.getPackageInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0) }.isSuccess
    }

    // ShizukuProvider's own extra name (private there, ShizukuProvider.java EXTRA_BINDER, Shizuku-API 13).
    private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"

    /** The process name before API 28: the first NUL-terminated entry of the process's own command line. */
    private fun legacyProcessName(): String? =
        runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrNull()

    /**
     * Asks for the binder from outside the provider process, once, on a background thread: the provider call can
     * start `:pluginhost`, which must not happen on the main thread. A binder that arrives later is picked up by the
     * receiver this registers. In the provider process this does nothing but try Sui.
     */
    fun ensureStarted() {
        if (started) return
        val context = appContext ?: return
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread {
            runCatching { if (!Sui.isSui()) Sui.init(context.packageName) }
            runCatching { ShizukuProvider.requestBinderForNonProviderProcess(context) }
        }.apply {
            name = "droidtop-shizuku-binder"
            isDaemon = true
            start()
        }
    }

    @Volatile
    private var appContext: Context? = null
}
