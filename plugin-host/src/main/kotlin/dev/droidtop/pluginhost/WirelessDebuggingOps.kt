package dev.droidtop.pluginhost

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.droidtop.runtime.tasks.BackendState
import dev.droidtop.runtime.tasks.ElevatedBackend
import dev.droidtop.runtime.tasks.ForceStopResult
import dev.droidtop.runtime.tasks.ShellOutput
import dev.droidtop.runtime.tasks.TaskPrivileges
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Elevated access through Android's own wireless debugging (docs/SPEC.md "Wireless debugging", Droidtop/tracker#816):
 * droidtop pairs with it once, with the code Android shows, and then talks to it as `adb` does, so it runs commands as
 * the shell user, as Shizuku does, with nothing else installed. Android 11 and later.
 *
 * - [state]: absent until paired; paired but Android's switch off is [BackendState.NEEDS_PERMISSION] (the switch turns
 *   itself off on another network and after a restart); paired and on is ready.
 * - Its key is droidtop's own, made on first pairing and kept in app storage; [forget] deletes it.
 * - Every call blocks on a socket: never on the main thread.
 */
class WirelessDebuggingOps(context: Context) : ElevatedBackend {
    private val app = context.applicationContext
    private val keyFile = File(app.filesDir, "$DIR/adbkey")
    private val certFile = File(app.filesDir, "$DIR/adbkey.crt")
    private val pairedFile = File(app.filesDir, "$DIR/paired")

    private val manager by lazy { Manager() }

    /** Whether this Android has wireless debugging at all. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val paired: Boolean get() = supported && pairedFile.isFile

    /** Android's own switch: Developer options > Wireless debugging. Readable by any app. */
    private fun switchOn(): Boolean = runCatching { Settings.Global.getInt(app.contentResolver, ADB_WIFI_ENABLED, 0) == 1 }.getOrDefault(false)

    override fun state(): BackendState = when {
        !paired -> BackendState.ABSENT
        switchOn() -> BackendState.READY
        else -> BackendState.NEEDS_PERMISSION
    }

    /** The shell user can do what Shizuku as the shell user can, except write other apps' files. */
    override fun capabilities(): TaskPrivileges =
        if (state() == BackendState.READY) TaskPrivileges(forceStop = true, shell = true, grantPermission = true, appOps = true) else TaskPrivileges.NONE

    override fun available(): TaskPrivileges = capabilities()

    override fun unavailableReason(): String? = when (state()) {
        BackendState.READY -> null
        BackendState.NEEDS_PERMISSION -> dev.droidtop.runtime.tasks.ElevatedAccess.WIRELESS_OFF
        BackendState.ABSENT -> "droidtop isn't paired with wireless debugging yet"
    }

    /**
     * Pairs with the code Android shows under "Pair device with pairing code". The pairing service's port is found on
     * the device itself (`_adb-tls-pairing._tcp`), so the person types only the six digits. Throws with a reason the
     * person can read when it fails.
     */
    fun pair(code: String, timeoutMs: Long = DISCOVER_MS) {
        check(supported) { "Wireless debugging needs Android 11 or later" }
        val port = discoverPort(PAIRING_SERVICE, timeoutMs) ?: error("No pairing code is showing. Open Pair device with pairing code first")
        ensureKey()
        // A false answer and an exception both mean the code was not taken.
        val answer: Any? = runCatching { manager.pair(LOCALHOST, port, code.trim()) }
            .getOrElse { throw IllegalStateException("That code didn't pair. Check it and try again", it) }
        check(answer != false) { "That code didn't pair. Check it and try again" }
        pairedFile.writeText(System.currentTimeMillis().toString())
    }

    /**
     * Whether Settings is showing a pairing code now: Android advertises the pairing service only while that dialog is
     * open. The pairing guide asks it to know when to take the code (Droidtop/tracker#363). Blocks up to [timeoutMs].
     */
    fun pairingCodeShowing(timeoutMs: Long): Boolean = supported && discoverPort(PAIRING_SERVICE, timeoutMs) != null

    /** Forgets the pairing: droidtop's key goes, and Android lists droidtop until the person removes it there. */
    fun forget() {
        runCatching { manager.disconnect() }
        File(app.filesDir, DIR).deleteRecursively()
    }

    override fun forceStop(packageName: String): ForceStopResult {
        val out = exec(listOf("am", "force-stop", packageName)) ?: return ForceStopResult.NoProvider
        return if (out.exit == 0) ForceStopResult.Stopped else ForceStopResult.Failed(out.stderr.ifBlank { out.stdout }.trim())
    }

    override fun grantPermission(packageName: String, permission: String): Boolean =
        exec(listOf("pm", "grant", packageName, permission))?.exit == 0

    override fun setAppOp(packageName: String, op: String, mode: String): Boolean =
        exec(listOf("appops", "set", packageName, op, mode))?.exit == 0

    /**
     * Runs [argv] as the shell user. adb's shell service gives one stream with no exit status, so the command's own
     * status is printed after it on a line of its own and read back. stderr arrives in the same stream.
     */
    @Synchronized
    override fun exec(argv: List<String>): ShellOutput? {
        if (state() != BackendState.READY) return null
        return runCatching {
            connect()
            val command = argv.joinToString(" ") { quote(it) } + " 2>&1; echo \"$EXIT_MARK\$?\""
            val stream = manager.openStream("shell:$command")
            val text = stream.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            runCatching { stream.close() }
            parse(text)
        }.onFailure { Log.w(TAG, "Wireless debugging command failed", it) }.getOrNull()
    }

    private fun connect() {
        if (manager.isConnected) return
        val port = discoverPort(CONNECT_SERVICE, DISCOVER_MS) ?: error("Wireless debugging isn't answering")
        val answer: Any? = manager.connect(LOCALHOST, port)
        check(answer != false && manager.isConnected) { "Wireless debugging refused droidtop" }
    }

    /** The port of [type] advertised on this device, found by Android's own service discovery. */
    private fun discoverPort(type: String, timeoutMs: Long): Int? {
        val nsd = app.getSystemService(NsdManager::class.java) ?: return null
        val found = CountDownLatch(1)
        var port: Int? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (port == null) port = resolved.port
                        found.countDown()
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                })
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = found.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            found.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return port
    }

    /** droidtop's own adb key and certificate, made once. */
    private fun ensureKey() {
        if (keyFile.isFile && certFile.isFile) return
        keyFile.parentFile?.mkdirs()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=droidtop")
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - DAY_MS), Date(now + CERT_VALID_MS), name, pair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(cert.encoded)
    }

    private inner class Manager : AbsAdbConnectionManager() {
        init {
            setApi(Build.VERSION.SDK_INT)
        }

        override fun getPrivateKey(): PrivateKey {
            ensureKey()
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        }

        override fun getCertificate(): Certificate {
            ensureKey()
            return certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        }

        override fun getDeviceName(): String = "droidtop"
    }

    companion object {
        private const val TAG = "droidtop.WirelessAdb"
        private const val DIR = "wireless_debugging"
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val LOCALHOST = "127.0.0.1"
        const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
        const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
        private const val DISCOVER_MS = 8_000L
        private const val DAY_MS = 86_400_000L
        private const val CERT_VALID_MS = 30L * 365 * DAY_MS
        internal const val EXIT_MARK = "__droidtop_exit:"

        /** One argument for `sh -c`, single-quoted. Pure. */
        internal fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

        /** The output before the exit line, and the status on it; a stream cut short reads as failed. Pure. */
        internal fun parse(text: String): ShellOutput {
            val at = text.lastIndexOf(EXIT_MARK)
            if (at < 0) return ShellOutput(-1, text, "")
            val code = text.substring(at + EXIT_MARK.length).trim().toIntOrNull() ?: -1
            return ShellOutput(code, text.substring(0, at), "")
        }
    }
}
