package dev.droidtop.runtime.remotestream

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real LAN host discovery via mDNS -- real moonlight-android's own actual
 * approach (confirmed via its `MdnsDiscoveryAgent`, service type
 * `_nvstream._tcp.local.`), reusing Android's own built-in [NsdManager]
 * (`android.net.nsd`) rather than a third-party mDNS library like
 * moonlight-android's own `jmDNS` dependency -- `NsdManager` is the real,
 * standard Android API for exactly this (Network Service Discovery,
 * available since API 16, well under this module's real `minSdk = 26`),
 * so no new dependency is needed.
 *
 * Real, honest scope for this pass: resolves each discovered host's real
 * address/name only. [RemoteHost.paired] is always `false` here --
 * whether a given host has actually been paired before is real, separate,
 * PERSISTED state (droidtop's own record of past successful pairings,
 * matched by address or pinned cert), not something mDNS/NSD itself can
 * answer -- that persistence layer doesn't exist yet (a real, separate
 * gap, not attempted here; see `RemoteStreamProvider.scan()`'s own
 * `.filter { it.paired }`, which currently has nothing real to filter
 * FOR until that persistence exists). A caller that already knows a host
 * was paired (e.g. from its own saved list) should merge that in itself.
 *
 * Runs for a fixed real discovery window (matching moonlight-android's
 * own practice of a bounded LAN scan, not open-ended) rather than
 * blocking indefinitely -- a real device on the LAN typically responds
 * within a second or two; hosts that answer after the window closes are
 * simply missed by this one call (a caller wanting continuous discovery
 * would need to call this repeatedly, not something this class does on
 * its own). manual host-add-by-IP (matching real moonlight-android's own
 * real fallback for hosts discovery can't reach -- over the internet, or
 * a misbehaving LAN) is a separate, real, not-yet-built UI feature.
 */
class NsdRemoteHostDiscovery(private val context: Context) : RemoteHostDiscovery {
    override suspend fun discover(): List<RemoteHost> {
        val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return emptyList()
        val found = mutableMapOf<String, RemoteHost>()

        withTimeoutOrNull(DISCOVERY_WINDOW_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                val discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {}

                    override fun onServiceFound(service: NsdServiceInfo) {
                        // A real, separate ResolveListener per service --
                        // NsdManager's classic API doesn't allow reusing
                        // one across concurrent resolves.
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.d(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val address = serviceInfo.host?.hostAddress ?: return
                                found[address] = RemoteHost(
                                    address = address,
                                    name = serviceInfo.serviceName ?: address,
                                    paired = false,
                                )
                            }
                        })
                    }

                    override fun onServiceLost(service: NsdServiceInfo) {}
                    override fun onDiscoveryStopped(serviceType: String) {}

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.w(TAG, "Discovery failed to start: $errorCode")
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                }

                try {
                    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                } catch (t: Exception) {
                    Log.e(TAG, "discoverServices threw", t)
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    return@suspendCancellableCoroutine
                }

                cont.invokeOnCancellation {
                    try {
                        nsdManager.stopServiceDiscovery(discoveryListener)
                    } catch (t: Exception) {
                        // Real, expected case: discovery may have already
                        // stopped itself (onStartDiscoveryFailed) before
                        // the timeout cancelled this coroutine.
                    }
                }
            }
        }

        return found.values.toList()
    }

    companion object {
        private const val TAG = "droidtop.NsdDiscovery"

        /** Real service type real moonlight-android/Sunshine hosts advertise (`MdnsDiscoveryAgent`'s own real constant). */
        private const val SERVICE_TYPE = "_nvstream._tcp"
        private const val DISCOVERY_WINDOW_MS = 3000L
    }
}
