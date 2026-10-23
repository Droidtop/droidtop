package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.pluginhost.DownloadJobs
import `in`.dragonbra.javasteam.steam.cdn.Fetcher
import `in`.dragonbra.javasteam.util.SteamKitWebRequestException
import java.io.File
import java.io.IOException

/**
 * Steam's downloads on droidtop's download engine (docs/SPEC.md 12a, "Everything moves"): JavaSteam (droidtop's fork,
 * vendor/javasteam) hands its depot chunks, web files and Workshop files to this [Fetcher] instead of fetching them
 * itself. Each is one engine transfer into the downloads area; JavaSteam reads or moves the file and deletes it, and
 * still decides which chunks and how many at once, and decrypts, decompresses and assembles them. The connection and
 * the web API stay requests on the Steam client's own HTTP client.
 */
internal class SteamFetcher(private val context: Context) : Fetcher {
    override suspend fun fetch(url: String, headers: Map<String, String>, name: String): File = try {
        DownloadJobs.fetchForJob(context, url, areaName(name), headers, tellJob = false)
    } catch (e: DownloadJobs.EngineFailure) {
        // A refusal is the status JavaSteam acts on (a 403 asks for a CDN token and tries again); any other failure is
        // one JavaSteam tries again on another server.
        val status = e.refusedStatus ?: throw IOException(e.message, e)
        throw SteamKitWebRequestException(e.message ?: "HTTP $status").apply { statusCode = status }
    }

    companion object {
        /** The start of the downloads-area names of Steam's downloads, which a discarded install clears. */
        const val AREA_PREFIX = "steam_"

        /** The downloads-area name of JavaSteam's [name] for a download: a bare file name. Pure. */
        fun areaName(name: String): String = AREA_PREFIX + name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
