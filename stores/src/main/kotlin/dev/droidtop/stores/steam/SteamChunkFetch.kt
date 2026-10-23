package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.pluginhost.DownloadJobs
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Steam's depot chunks on droidtop's download engine (docs/SPEC.md 12a, "Everything moves"): JavaSteam's CDN client
 * asks for each chunk through the Steam client's HTTP client, and this interceptor has the engine fetch a chunk instead,
 * handing its bytes back as the answer. JavaSteam still decrypts, decompresses and assembles every chunk, and its
 * `DepotDownloader` still decides which chunks and how many at once. Every other request (manifests, the web API) goes
 * out as a request.
 */
internal class SteamChunkFetch(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val chunk = chunkOf(request.url.encodedPath)
        if (chunk == null || request.method != "GET") return chain.proceed(request)
        val name = areaName(chunk.first, chunk.second)
        val headers = request.headers.names().associateWith { request.header(it).orEmpty() }
        val fetched = try {
            runBlocking(Dispatchers.IO) {
                val fetch = async { DownloadJobs.fetchForJob(context, request.url.toString(), name, headers, tellJob = false) }
                // JavaSteam cancels its call to stop a chunk (a pause, a failed install): the engine's transfer stops with it.
                val watch = launch {
                    while (isActive) {
                        if (chain.call().isCanceled()) fetch.cancel()
                        delay(CANCEL_CHECK_MS)
                    }
                }
                try {
                    fetch.await()
                } finally {
                    watch.cancel()
                }
            }
        } catch (e: DownloadJobs.EngineFailure) {
            val status = e.refusedStatus ?: throw IOException(e.message, e)
            // The status JavaSteam acts on: a 403 makes it fetch a CDN auth token and ask again.
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message(e.message.orEmpty())
                .body(ByteArray(0).toResponseBody(null)).build()
        } catch (e: CancellationException) {
            throw IOException("Canceled", e)
        }
        val bytes = try {
            fetched.readBytes()
        } finally {
            // Read, or not: the fetched copy is spent, and a chunk asked for again is fetched again.
            DownloadJobs.discardForJob(context, name)
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(bytes.toResponseBody(OCTET_STREAM)).build()
    }

    companion object {
        private const val CANCEL_CHECK_MS = 200L
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val CHUNK_PATH = Regex("""/depot/(\d+)/chunk/([0-9A-Fa-f]+)$""")

        /** The start of the downloads-area names of Steam's chunks, which a discarded install clears. */
        const val AREA_PREFIX = "steam_"

        /** The depot and chunk id a CDN path asks for (`.../depot/<depot>/chunk/<hex>`), or null for any other request. Pure. */
        fun chunkOf(path: String): Pair<Int, String>? {
            val match = CHUNK_PATH.find(path) ?: return null
            val depot = match.groupValues[1].toIntOrNull() ?: return null
            return depot to match.groupValues[2].lowercase()
        }

        /** A chunk's name in the downloads area. Pure. */
        fun areaName(depot: Int, chunkHex: String): String = "$AREA_PREFIX${depot}_$chunkHex"
    }
}
