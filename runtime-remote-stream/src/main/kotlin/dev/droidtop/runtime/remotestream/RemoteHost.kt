package dev.droidtop.runtime.remotestream

/** A discovered or manually-added GameStream-protocol host (Sunshine, Apollo, or legacy NVIDIA GFE). */
data class RemoteHost(
    val address: String,
    val name: String,
    val paired: Boolean,
    val serverCertHash: String? = null,
)

/** One app Sunshine (or Apollo) has configured as streamable on a given [RemoteHost]. */
data class RemoteApp(
    val hostAddress: String,
    val appId: Int,
    val name: String,
    val artworkUri: String? = null,
)

enum class PairState { NOT_PAIRED, PIN_ENTRY_REQUIRED, PAIRED, FAILED }

/**
 * LAN host discovery. NOT part of vendor/moonlight-common-c itself — that
 * library is transport/protocol only. Every platform Moonlight client
 * (moonlight-android in particular) layers its own discovery on top; this
 * needs to be ported/reimplemented here, not pulled in "for free."
 *
 * moonlight-android's discovery approach is the direct reference: broadcast
 * probes on the LAN + optionally mDNS, matching hosts that answer the
 * GameStream/Sunshine `serverinfo` HTTPS endpoint.
 */
interface RemoteHostDiscovery {
    suspend fun discover(): List<RemoteHost>
}

/**
 * Thin Kotlin wrapper over the native moonlight-common-c bindings
 * (remotestream_jni.cpp). Pairing, app-list retrieval, and stream launch —
 * see native/src/remotestream_jni.cpp for what's actually implemented
 * (nothing, yet; this is the call surface it needs to satisfy).
 */
class MoonlightClient {
    suspend fun pair(host: RemoteHost, pin: String): PairState {
        TODO("Call nativePair; Sunshine also accepts pairing via its POST /api/pin REST endpoint as an alternative to the classic PIN-entry-on-host-UI flow — worth using that instead when the host is confirmed to be Sunshine")
    }

    suspend fun fetchAppList(host: RemoteHost): List<RemoteApp> {
        TODO("Call nativeFetchAppList")
    }

    fun startStream(host: RemoteHost, app: RemoteApp) {
        TODO("Call nativeStartStream; render decoded frames — likely onto a DisplayOutput like any other window, so a remote stream can be merged onto the desktop or popped to its own screen exactly like a local app")
    }

    private external fun nativePair(hostAddress: String, pin: String): Boolean
    private external fun nativeFetchAppList(hostAddress: String): Array<String>?
    private external fun nativeStartStream(hostAddress: String, appId: Int): Boolean

    companion object {
        init {
            System.loadLibrary("remotestream")
        }
    }
}
