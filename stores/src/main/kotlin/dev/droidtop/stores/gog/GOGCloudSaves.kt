package dev.droidtop.stores.gog

import android.content.Context
import dev.droidtop.library.stores.SaveConflictResolver
import dev.droidtop.library.stores.SaveSyncResult
import dev.droidtop.library.stores.WinePrefixLocation
import dev.droidtop.net.AtomicWrite
import dev.droidtop.runtime.util.runCatchingCancellable
import dev.droidtop.stores.gog.api.GOGApiClient
import dev.droidtop.stores.gog.api.GOGManifestParser
import dev.droidtop.stores.util.CloudSavePlan.Decision
import dev.droidtop.stores.util.CloudSavePlan.Facts
import dev.droidtop.stores.util.CloudSaveRun
import dev.droidtop.stores.util.CloudSaveRun.Folder
import dev.droidtop.stores.util.CloudSaveState
import dev.droidtop.stores.util.StoreHttp
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * GOG cloud saves of one game (docs/SPEC.md 7g, "Stores", Droidtop/tracker#224): the calls of GameNative's
 * GOGCloudSavesManager and its save locations (GPL-3.0, after gogdl), with the decision made by
 * [dev.droidtop.stores.util.CloudSavePlan] from the state kept at the last sync instead of GameNative's newer-timestamp
 * rule, so a conflict is the person's and never settled by a clock.
 *
 * Where the saves are is the game's own: its build manifest names its GOG client (id and secret), GOG's remote config
 * for that client names its save locations (`<?INSTALL?>`, `<?APPLICATION_DATA_LOCAL?>` ...), and a game whose cloud is
 * on with no locations keeps the Galaxy SDK's folder. Each location's files are stored in GOG's cloud under the
 * location's name, gzipped; GOG lists each with the MD5 of what it stores, so this device's fingerprint of a file is
 * the MD5 of the same gzip ([fingerprint]) and the state keeps the cloud's own beside it.
 */
internal object GOGCloudSaves {
    private const val CLOUD = "https://cloudstorage.gog.com/v1"
    private const val REMOTE_CONFIG = "https://remote-config.gog.com/components/galaxy_client/clients"
    private const val USER_AGENT = "GOGGalaxyCommunicationService/2.0.13.27 (Windows_32bit) dont_sync_marker/true installation_source/gog"

    /** The MD5 GOG lists for a file that was deleted from the cloud. */
    const val DELETED_MD5 = "aadd86936a80ee8a369579c3926f1b3c"

    const val LABEL = "GOG cloud"
    private const val TAG = "GOGCloudSaves"

    /** One save location as GOG's remote config names it: [name] prefixes its files in the cloud, [template] is the folder. */
    data class Location(val name: String, val template: String)

    /** A game's GOG client and its save locations, kept per game so its save folder is known without the network. */
    data class Config(val clientId: String, val clientSecret: String, val locations: List<Location>) {
        fun encode(): String = JSONObject()
            .put("clientId", clientId)
            .put("clientSecret", clientSecret)
            .put("locations", JSONArray(locations.map { JSONObject().put("name", it.name).put("location", it.template) }))
            .toString()

        companion object {
            fun decode(text: String): Config? = runCatching {
                val json = JSONObject(text)
                val array = json.getJSONArray("locations")
                Config(
                    json.getString("clientId"),
                    json.optString("clientSecret", ""),
                    (0 until array.length()).map { array.getJSONObject(it).let { o -> Location(o.optString("name", ""), o.getString("location")) } },
                )
            }.getOrNull()
        }
    }

    /**
     * The save locations in GOG's remote config ([json]) for the Windows build: null when the game's cloud saves are
     * off, the Galaxy SDK's own folder when they are on with no locations named. Pure.
     */
    fun parseLocations(json: String, clientId: String): List<Location>? {
        val storage = runCatching { JSONObject(json).getJSONObject("content").getJSONObject("Windows").getJSONObject("cloudStorage") }
            .getOrNull() ?: return null
        if (!storage.optBoolean("enabled", false)) return null
        val array = storage.optJSONArray("locations")
        val named = (0 until (array?.length() ?: 0)).mapNotNull { index ->
            val item = array!!.getJSONObject(index)
            item.optString("location", "").takeIf { it.isNotBlank() }?.let { Location(item.optString("name", "__default"), it) }
        }
        // The Galaxy SDK keeps the files of a game that names no folder here, unprefixed in its cloud (GameNative's fallback).
        return named.ifEmpty { listOf(Location("", "%LOCALAPPDATA%/GOG.com/Galaxy/Applications/$clientId/Storage/Shared/Files")) }
    }

    /**
     * The folder [template] names in [prefix], with the game at [installDir]: GOG's `<?VARIABLE?>` names and the
     * Windows variables they stand for, as Wine lays out a user's folders. Null when it names a variable this device
     * does not have (the install folder of a game not installed here) or leaves both the prefix and the game. Pure.
     */
    fun resolve(template: String, prefix: WinePrefixLocation, installDir: File?): File? {
        val home = File(File(prefix.prefixDir, "drive_c/users"), prefix.user)
        val variables = mapOf(
            "<?INSTALL?>" to installDir?.path,
            "<?SAVED_GAMES?>" to File(home, "Saved Games").path,
            "<?APPLICATION_DATA_LOCAL?>" to File(home, "AppData/Local").path,
            "<?APPLICATION_DATA_LOCAL_LOW?>" to File(home, "AppData/LocalLow").path,
            "<?APPLICATION_DATA_ROAMING?>" to File(home, "AppData/Roaming").path,
            "<?DOCUMENTS?>" to File(home, "Documents").path,
            "%USERPROFILE%" to home.path,
            "%LOCALAPPDATA%" to File(home, "AppData/Local").path,
            "%APPDATA%" to File(home, "AppData/Roaming").path,
        )
        var path = template.replace('\\', '/')
        for ((name, value) in variables) {
            if (!path.contains(name, ignoreCase = true)) continue
            path = path.replace(name, (value ?: return null).replace('\\', '/'), ignoreCase = true)
        }
        if (path.contains("<?") || path.contains('%')) return null
        val parts = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        val folder = File("/" + parts.joinToString("/"))
        val inside = listOfNotNull(prefix.prefixDir, installDir).any { root -> folder.path == root.path || folder.path.startsWith(root.path + "/") }
        return folder.takeIf { inside }
    }

    /** GOG's cloud list ([json]) as files by name, with deleted ones left out: GOG's MD5, size and time. Pure. */
    fun parseListing(json: String): Map<String, Facts> {
        val array = runCatching { JSONArray(json.ifBlank { "[]" }) }.getOrElse { throw IllegalStateException("GOG's cloud list could not be read") }
        val files = LinkedHashMap<String, Facts>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val name = item.optString("name", "")
            val hash = item.optString("hash", "")
            if (name.isEmpty() || hash.isEmpty() || hash == DELETED_MD5) continue
            files[name] = Facts(hash, item.optLong("bytes", 0L), parseTime(item.optString("last_modified", "")))
        }
        return files
    }

    /** GOG's time ("2026-04-02T20:34:00.123456+00:00") in epoch ms; 0 when it is none. Pure. */
    fun parseTime(text: String): Long = runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrDefault(0L)

    /** The MD5 of [file] gzipped the way droidtop uploads it ([gzip]): what GOG lists for a file droidtop sent. */
    fun fingerprint(file: File): String = md5(gzip(file.readBytes()))

    fun md5(bytes: ByteArray): String = CloudSaveRun.hex(MessageDigest.getInstance("MD5").digest(bytes))

    /** [bytes] gzipped with no name and a zero time, so the same file always gives the same gzip (gogdl's choice too). */
    fun gzip(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun configFile(context: Context, gameId: String): File = File(context.filesDir, "gog/cloud/${gameId.filter { it.isLetterOrDigit() }}.config.json")

    /** The config kept at the last sync; no network. */
    fun keptConfig(context: Context, gameId: String): Config? =
        configFile(context, gameId).takeIf { it.isFile }?.let { Config.decode(it.readText()) }

    /**
     * [gameId]'s GOG client and save locations, read from GOG and kept; the kept one when GOG cannot be reached. Null
     * when its cloud saves are off or its build names no GOG client.
     */
    suspend fun config(context: Context, gameId: String): Config? = withContext(Dispatchers.IO) {
        val fresh = runCatchingCancellable {
            val parser = GOGManifestParser()
            val client = GOGApiClient(context.applicationContext, parser)
            val build = client.latestWindowsBuild(gameId).getOrThrow() ?: return@runCatchingCancellable null
            val manifest = client.fetchManifest(build.link).getOrThrow()
            if (manifest.clientId.isBlank() || manifest.clientSecret.isBlank()) return@runCatchingCancellable null
            val request = Request.Builder().url("$REMOTE_CONFIG/${manifest.clientId}?component_version=2.0.45").build()
            val body = StoreHttp.http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "GOG's save locations could not be read (HTTP ${response.code})" }
                response.body?.string().orEmpty()
            }
            parseLocations(body, manifest.clientId)?.let { Config(manifest.clientId, manifest.clientSecret, it) }
        }
        fresh.onSuccess { config ->
            val file = configFile(context, gameId)
            if (config != null) AtomicWrite.text(file, config.encode()) else file.delete()
        }
        fresh.getOrElse {
            Timber.tag(TAG).w(it, "Could not read GOG's save locations of $gameId")
            keptConfig(context, gameId) ?: throw it
        }
    }

    fun folders(config: Config, prefix: WinePrefixLocation, installDir: File?): List<Folder> =
        config.locations.mapNotNull { location -> resolve(location.template, prefix, installDir)?.let { Folder(location.name, it) } }

    /**
     * One sync of [gameId]'s saves between [prefix] and GOG's cloud. Null when the game keeps no cloud saves or none of
     * its folders is in this prefix.
     */
    suspend fun run(
        context: Context,
        gameId: String,
        title: String,
        prefix: WinePrefixLocation,
        installDir: File?,
        onConflict: SaveConflictResolver?,
    ): SaveSyncResult? = withContext(Dispatchers.IO) {
        val config = config(context, gameId) ?: return@withContext null
        val folders = folders(config, prefix, installDir).ifEmpty { return@withContext null }
        val credentials = GOGAuthManager.getGameCredentials(context, config.clientId, config.clientSecret).getOrThrow()
        val base = "$CLOUD/${credentials.userId}/${config.clientId}"
        fun request(path: String = "") = Request.Builder().url(if (path.isEmpty()) base else "$base/$path")
            .header("Authorization", "Bearer ${credentials.accessToken}")
            .header("User-Agent", USER_AGENT)
            .header("X-Object-Meta-User-Agent", USER_AGENT)

        val listing = StoreHttp.http.newCall(request().header("Accept", "application/json").build()).execute().use { response ->
            check(response.isSuccessful) { "GOG's cloud would not list the saves (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }
        // Only the names inside this game's folders; another location's files are not this sync's.
        val remote = parseListing(listing).filterKeys { key -> CloudSaveRun.localFile(folders, key) != null }

        val stateFile = CloudSaveState.file(context.filesDir, "gog", gameId)
        val state = CloudSaveState.load(stateFile)
        val files = CloudSaveRun.scan(folders)
        val local = files.mapValues { (key, file) ->
            val known = state.files[key]
            val size = file.length()
            val time = file.lastModified()
            Facts(if (known != null && known.size == size && known.timeMs == time) known.sha else fingerprint(file), size, time)
        }

        val (decision, unsettled) = CloudSaveRun.decide(state, local, remote, title, LABEL, onConflict)
        if (decision == null) return@withContext unsettled
        val kept = HashMap(state.files.filterKeys { it in local || it in remote })
        val keptRemote = HashMap(state.remote.filterKeys { it in kept })
        // Files both sides already agree on keep their entry (or gain one).
        fun agreeOn(key: String, mine: Facts, theirs: String) {
            kept[key] = mine
            if (theirs == mine.sha) keptRemote.remove(key) else keptRemote[key] = theirs
        }
        for ((key, mine) in local) remote[key]?.let { if (it.sha == mine.sha) agreeOn(key, mine, it.sha) }

        val result = when (decision) {
            Decision.UpToDate -> CloudSaveRun.upToDate()
            is Decision.Download -> {
                var done = 0
                var removed = 0
                var failed = 0
                for (key in decision.fetch) {
                    val target = CloudSaveRun.localFile(folders, key)
                    val theirs = remote.getValue(key)
                    val ok = target != null && runCatchingCancellable { fetch(request(key).build(), target, theirs.timeMs) }
                        .onFailure { Timber.tag(TAG).w(it, "Could not download $key") }.getOrDefault(false)
                    if (ok) {
                        done++
                        agreeOn(key, Facts(fingerprint(target!!), target.length(), target.lastModified()), theirs.sha)
                    } else {
                        failed++
                    }
                }
                for (key in decision.removeLocal) {
                    if (files[key]?.delete() == true) {
                        removed++
                        kept.remove(key)
                        keptRemote.remove(key)
                    } else {
                        failed++
                    }
                }
                CloudSaveRun.downloaded(LABEL, done, removed, failed, decision.fetch.size + decision.removeLocal.size)
            }
            is Decision.Upload -> {
                var done = 0
                var removed = 0
                var failed = 0
                for (key in decision.send) {
                    val file = files.getValue(key)
                    val ok = runCatchingCancellable { send(request(key), file) }
                        .onFailure { Timber.tag(TAG).w(it, "Could not upload $key") }.getOrDefault(false)
                    if (ok) {
                        done++
                        val mine = local.getValue(key)
                        agreeOn(key, mine, mine.sha)
                    } else {
                        failed++
                    }
                }
                for (key in decision.removeRemote) {
                    val ok = runCatchingCancellable {
                        StoreHttp.http.newCall(request(key).delete().build()).execute().use { it.isSuccessful || it.code == 404 }
                    }.getOrDefault(false)
                    if (ok) {
                        removed++
                        kept.remove(key)
                        keptRemote.remove(key)
                    } else {
                        failed++
                    }
                }
                CloudSaveRun.uploaded(LABEL, done, removed, failed, decision.send.size + decision.removeRemote.size)
            }
            is Decision.Conflict -> error("A conflict is settled before it is applied")
        }
        CloudSaveState.save(stateFile, CloudSaveState(kept, keptRemote))
        result
    }

    /** Downloads one file over [target] through a file beside it; false when GOG did not send it. */
    private fun fetch(request: Request, target: File, timeMs: Long): Boolean =
        StoreHttp.http.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null || target.isDirectory) return@use false
            target.parentFile?.mkdirs()
            val temp = CloudSaveRun.tempFor(target)
            try {
                temp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                CloudSaveRun.replace(temp, target, timeMs)
            } finally {
                temp.delete()
            }
        }

    /** Uploads [file] gzipped, the way Galaxy and gogdl send it; false when GOG refused it. */
    private fun send(request: Request.Builder, file: File): Boolean {
        val gz = gzip(file.readBytes())
        val etag = md5(gz)
        val call = request
            .put(gz.toRequestBody("application/octet-stream".toMediaType()))
            .header("Content-Encoding", "gzip")
            .header("Etag", etag)
            .header("X-Object-Meta-LocalLastModified", Instant.ofEpochMilli(file.lastModified()).toString())
            .build()
        return StoreHttp.http.newCall(call).execute().use { it.isSuccessful }
    }
}
