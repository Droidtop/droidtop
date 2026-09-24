package dev.droidtop.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** What the device VPN is doing, as the container page states it. */
sealed interface VpnState {
    data object Off : VpnState
    data object Connected : VpnState

    /**
     * Up and holding traffic, but nothing answers at the container's VPN
     * socket: its VPN client is not running (or the container is not).
     * Traffic is dropped rather than sent around the tunnel.
     */
    data object NoEndpoint : VpnState
    data class Failed(val message: String) : VpnState
}

/**
 * The device VPN a container serves (docs/SPEC.md 4a). Android hands
 * this service the device's tun fd; vendor/hev-socks5-tunnel turns every
 * packet in it into a SOCKS5 connection, and [SocksUnixRelay] carries each
 * one to the SOCKS5 endpoint the container's own VPN client serves at
 * `vpn.sock` in the shared socket directory. No root is involved on the
 * device side, whichever backend runs the container.
 *
 * droidtop itself is always outside the tunnel (with per-app routing, it
 * is never among the allowed apps; without, it is the one disallowed app):
 * under proot the container's VPN client is a droidtop process, and its
 * own traffic to the VPN server must not loop back into the tunnel it
 * serves.
 *
 * While on, all of the device's routes point into the tunnel, so when the
 * endpoint goes away traffic stops instead of silently leaving on the bare
 * network; the service stays up in [VpnState.NoEndpoint] until the person
 * turns it off. Android's own always-on and "block connections without
 * VPN" settings are the platform's, linked from the container page; an
 * always-on start carries no extras and uses what [VpnPrefs] recorded.
 */
class DroidtopVpnService : VpnService() {
    private var scope: CoroutineScope? = null
    private var tun: ParcelFileDescriptor? = null
    private var relay: SocksUnixRelay? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            VpnPrefs.setEnabled(this, false)
            shutDown()
            // By id: a connect sent right behind this one (switching the
            // VPN to another container, a changed app list) keeps the
            // service alive to handle it.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (scope == null) connect()
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the person revoked droidtop's in
        // Android's settings: droidtop's switch follows.
        VpnPrefs.setEnabled(this, false)
        shutDown()
        stopSelf()
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun connect() {
        val job = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = job
        job.launch {
            val config = VpnPrefs.read(this@DroidtopVpnService)
            val socket = config.socketPath?.let(::File)
            if (socket == null) {
                fail("no container is set to serve the VPN; choose one on its Containers page")
                return@launch
            }
            val started = runCatching { establish(config, socket) }
            if (started.isFailure) {
                fail(started.exceptionOrNull()?.message ?: "the VPN could not start")
                return@launch
            }
            // The state follows the endpoint for as long as the VPN is up.
            while (isActive) {
                _state.value = if (SocksUnixRelay.answers(socket)) VpnState.Connected else VpnState.NoEndpoint
                delay(HEALTH_INTERVAL_MS)
            }
        }
    }

    private fun establish(config: VpnPrefs.Config, socket: File) {
        val builder = Builder()
            .setSession(config.containerId ?: "droidtop")
            .setMtu(TunnelConfig.MTU)
            .addAddress(TunnelConfig.IPV4, 32)
            .addAddress(TunnelConfig.IPV6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(TunnelConfig.DNS)
        if (config.allowedApps.isEmpty()) {
            builder.addDisallowedApplication(packageName)
        } else {
            config.allowedApps.filter { it != packageName }.forEach { app ->
                runCatching { builder.addAllowedApplication(app) }
                    .onFailure { Log.w(TAG, "$app is not installed; left out of the VPN") }
            }
        }
        val fd = builder.establish() ?: error("Android has not given droidtop permission to be the VPN")
        tun = fd
        val newRelay = SocksUnixRelay(socket, getSystemService(ConnectivityManager::class.java))
        relay = newRelay
        val configFile = File(cacheDir, "vpn-tunnel.yml")
        configFile.writeText(TunnelConfig.yaml(newRelay.port))
        check(TunnelNative.TProxyStartService(configFile.absolutePath, fd.fd)) { "the VPN's packet stack did not start" }
        Log.i(TAG, "VPN up through ${socket.path} (relay on 127.0.0.1:${newRelay.port})")
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        _state.value = VpnState.Failed(message)
        closeTunnel()
    }

    private fun closeTunnel() {
        runCatching { if (TunnelNative.TProxyIsRunning()) TunnelNative.TProxyStopService() }
        relay?.close()
        relay = null
        runCatching { tun?.close() }
        tun = null
    }

    private fun shutDown() {
        scope?.cancel()
        scope = null
        closeTunnel()
        _state.value = VpnState.Off
    }

    companion object {
        private const val TAG = "droidtop.vpn"
        private const val HEALTH_INTERVAL_MS = 3_000L
        private const val ACTION_CONNECT = "dev.droidtop.app.vpn.CONNECT"
        private const val ACTION_DISCONNECT = "dev.droidtop.app.vpn.DISCONNECT"

        private val _state = MutableStateFlow<VpnState>(VpnState.Off)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        /**
         * Starts the VPN with what [VpnPrefs] holds. The caller has
         * already had [VpnService.prepare]'s consent answered.
         */
        fun start(context: Context) {
            context.startService(Intent(context, DroidtopVpnService::class.java).setAction(ACTION_CONNECT))
        }

        fun stop(context: Context) {
            if (_state.value == VpnState.Off) return
            // From the background (a mode switched off at process start)
            // Android refuses startService; the component being disabled
            // then ends the service instead.
            runCatching {
                context.startService(Intent(context, DroidtopVpnService::class.java).setAction(ACTION_DISCONNECT))
            }
        }
    }
}

/** Which container serves the VPN, whether it is on, and which apps it carries. */
object VpnPrefs {
    private const val FILE = "device_vpn"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CONTAINER = "container"
    private const val KEY_SOCKET = "socket"
    private const val KEY_APPS = "allowed_apps"

    data class Config(
        val enabled: Boolean,
        val containerId: String?,
        val socketPath: String?,
        /** Empty means every app but droidtop. */
        val allowedApps: Set<String>,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(context: Context): Config {
        val p = prefs(context)
        return Config(
            enabled = p.getBoolean(KEY_ENABLED, false),
            containerId = p.getString(KEY_CONTAINER, null),
            socketPath = p.getString(KEY_SOCKET, null),
            allowedApps = p.getStringSet(KEY_APPS, emptySet()).orEmpty().toSet(),
        )
    }

    fun serveFrom(context: Context, containerId: String, socket: File) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_CONTAINER, containerId)
            .putString(KEY_SOCKET, socket.absolutePath)
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setAllowedApps(context: Context, apps: Set<String>) {
        prefs(context).edit().putStringSet(KEY_APPS, apps).apply()
    }
}
