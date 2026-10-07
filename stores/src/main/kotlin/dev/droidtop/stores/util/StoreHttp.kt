package dev.droidtop.stores.util

import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * The one HTTP client the stores share (GameNative's `Net`, GPL-3.0, without
 * its DNS-over-HTTPS fallback: name resolution is the platform's job, and
 * Android's own Private DNS setting is where a person chooses that).
 */
internal object StoreHttp {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** [http] with room for [parallelDownloads] requests to one host at once and no call deadline. */
    fun httpForParallelDownloads(parallelDownloads: Int): OkHttpClient {
        val hostConcurrency = parallelDownloads.coerceIn(4, 32)
        val dispatcher = Dispatcher().apply {
            maxRequestsPerHost = hostConcurrency
            maxRequests = maxOf(64, hostConcurrency * 2)
        }
        return http.newBuilder()
            .dispatcher(dispatcher)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            // Many parallel streams, each its own connection: HTTP/2 would put them on one.
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }
}
