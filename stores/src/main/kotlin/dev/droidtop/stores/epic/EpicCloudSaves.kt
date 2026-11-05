package dev.droidtop.stores.epic

import android.content.Context
import dev.droidtop.library.stores.SaveConflictResolver
import dev.droidtop.library.stores.SaveSyncResult
import dev.droidtop.library.stores.WinePrefixLocation
import dev.droidtop.runtime.util.runCatchingCancellable
import dev.droidtop.stores.data.EpicGame
import dev.droidtop.stores.epic.manifest.BinaryManifest
import dev.droidtop.stores.epic.manifest.ChunkDataList
import dev.droidtop.stores.epic.manifest.ChunkInfo
import dev.droidtop.stores.epic.manifest.ChunkPart
import dev.droidtop.stores.epic.manifest.CustomFields
import dev.droidtop.stores.epic.manifest.EpicManifest
import dev.droidtop.stores.epic.manifest.FileManifest
import dev.droidtop.stores.epic.manifest.FileManifestList
import dev.droidtop.stores.epic.manifest.ManifestMeta
import dev.droidtop.stores.util.CloudSavePlan.Decision
import dev.droidtop.stores.util.CloudSavePlan.Facts
import dev.droidtop.stores.util.CloudSaveRun
import dev.droidtop.stores.util.CloudSaveRun.Folder
import dev.droidtop.stores.util.CloudSaveState
import dev.droidtop.stores.util.StoreHttp
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Epic cloud saves of one game (docs/SPEC.md 7g, "Stores", Droidtop/tracker#224): the save-sync calls and the chunked
 * save format of GameNative's EpicCloudSavesManager (GPL-3.0, after Legendary), with the decision made by
 * [dev.droidtop.stores.util.CloudSavePlan] from the state kept at the last sync instead of a newer-timestamp rule.
 *
 * Epic keeps a game's saves as snapshots: each upload is a manifest (the files, their SHA-1 and which 1 MiB chunks hold
 * them) plus its chunks. The cloud's side of the plan is the newest manifest's file list; a download rebuilds the files
 * that differ from its chunks, an upload sends a new snapshot of every save file here. The folder is the game's
 * `CloudSaveFolder` (`{AppData}`, `{InstallDir}`, `{EpicId}` ...) in the game's Wine prefix.
 */
internal object EpicCloudSaves {
    private const val SAVESYNC = "https://datastorage-public-service-liveegs.live.use1a.on.epicgames.com/api/v1/access/egstore/savesync"
    private const val CHUNK_SIZE = 1024 * 1024
    private const val CHUNK_MAGIC = 0xB1FE3AA2.toInt()

    const val LABEL = "Epic cloud"
    private const val TAG = "EpicCloudSaves"

    /**
     * [template] (the game's `CloudSaveFolder`) as a folder in [prefix]: Legendary's path variables, case-insensitive,
     * mapped the way Wine lays out a user's folders. Null when it names a variable this device does not have or leaves
     * both the prefix and the game. Pure.
     */
    fun resolve(template: String, prefix: WinePrefixLocation, installDir: File?, accountId: String, appName: String): File? {
        if (template.isBlank()) return null
        val home = File(File(prefix.prefixDir, "drive_c/users"), prefix.user)
        val local = File(home, "AppData/Local").path
        val variables = mapOf(
            "{installdir}" to installDir?.path,
            "{epicid}" to accountId,
            "{appname}" to appName,
            // Legendary: {AppData} is %LOCALAPPDATA%.
            "{appdata}" to local,
            "{localappdata}" to local,
            "{roamingappdata}" to File(home, "AppData/Roaming").path,
            "{userdir}" to File(home, "Documents").path,
            "{usersavedgames}" to File(home, "Saved Games").path,
            "{userprofile}" to home.path,
        )
        var path = template.replace('\\', '/')
        for ((name, value) in variables) {
            if (!path.contains(name, ignoreCase = true)) continue
            path = path.replace(name, (value ?: return null).replace('\\', '/'), ignoreCase = true)
        }
        if (path.contains('{')) return null
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

    /** One file in Epic's save-sync list: its read link and when it last changed (ISO text, which sorts by time). */
    data class Listed(val readLink: String?, val lastModified: String)

    /** Epic's save-sync list ([json]) by path. Pure. */
    fun parseListing(json: String): Map<String, Listed> {
        val files = JSONObject(json.ifBlank { "{}" }).optJSONObject("files") ?: return emptyMap()
        return files.keys().asSequence().associateWith { key ->
            val item = files.getJSONObject(key)
            Listed(item.optString("readLink", "").ifEmpty { null }, item.optString("lastModified", ""))
        }
    }

    /** The newest snapshot's manifest in [listing], by when it last changed. Pure. */
    fun latestManifest(listing: Map<String, Listed>): Pair<String, Listed>? =
        listing.entries.filter { it.key.endsWith(".manifest") }.maxByOrNull { it.value.lastModified }?.toPair()

    /**
     * Where [chunk] of the snapshot [manifestPath] is in [listing]: beside the manifest's first three path parts
     * (Legendary's rule), else any listed file ending in the chunk's path.
     */
    fun chunkPath(listing: Map<String, Listed>, manifestPath: String, chunk: ChunkInfo): String? {
        val own = manifestPath.split('/', limit = 4).take(3).joinToString("/") + "/" + chunk.getPath()
        if (own in listing) return own
        return listing.keys.firstOrNull { it.endsWith("/" + chunk.getPath()) }
    }

    /** The files of a snapshot as the plan's cloud side: SHA-1, size and the snapshot's time. Pure. */
    fun remoteFacts(manifest: EpicManifest, timeMs: Long): Map<String, Facts> =
        manifest.fileManifestList?.elements.orEmpty().associate { file ->
            file.filename.replace('\\', '/') to Facts(CloudSaveRun.hex(file.hash), file.fileSize, timeMs)
        }

    fun sha1(bytes: ByteArray): String = CloudSaveRun.hex(MessageDigest.getInstance("SHA-1").digest(bytes))

    /**
     * One sync of [game]'s saves between [prefix] and Epic's cloud. Null when the game keeps no cloud saves or its
     * folder is not in this prefix.
     */
    suspend fun run(
        context: Context,
        game: EpicGame,
        title: String,
        prefix: WinePrefixLocation,
        onConflict: SaveConflictResolver?,
    ): SaveSyncResult? = withContext(Dispatchers.IO) {
        if (!game.cloudSaveEnabled || game.saveFolder.isBlank()) return@withContext null
        val credentials = EpicAuthManager.getStoredCredentials(context).getOrThrow()
        val installDir = game.installPath.takeIf { game.isInstalled && it.isNotBlank() }?.let(::File)
        val dir = resolve(game.saveFolder, prefix, installDir, credentials.accountId, game.appName) ?: return@withContext null
        val folders = listOf(Folder("", dir))
        val base = "$SAVESYNC/${credentials.accountId}/${game.appName}/"
        fun authed(url: String) = Request.Builder().url(url).header("Authorization", "Bearer ${credentials.accessToken}")

        val listing = StoreHttp.http.newCall(authed(base).get().build()).execute().use { response ->
            check(response.isSuccessful) { "Epic would not list the saves (HTTP ${response.code})" }
            parseListing(response.body?.string().orEmpty())
        }
        val latest = latestManifest(listing)
        val manifest = latest?.let { (_, listed) ->
            EpicManifest.readAll(get(listed.readLink ?: error("Epic gave no link to the newest save")))
        }
        val remote = manifest?.let { remoteFacts(it, parseTime(latest!!.second.lastModified)) }.orEmpty()

        val stateFile = CloudSaveState.file(context.filesDir, "epic", game.appName)
        val state = CloudSaveState.load(stateFile)
        // An empty file has no chunk to hold it, so it is not a save Epic keeps.
        val files = CloudSaveRun.scan(folders).filterValues { it.length() > 0 }
        val local = files.mapValues { (key, file) ->
            val known = state.files[key]
            val size = file.length()
            val time = file.lastModified()
            Facts(if (known != null && known.size == size && known.timeMs == time) known.sha else sha1(file.readBytes()), size, time)
        }

        val (decision, unsettled) = CloudSaveRun.decide(state, local, remote, title, LABEL, onConflict)
        if (decision == null) return@withContext unsettled
        val kept = HashMap(state.files.filterKeys { it in local || it in remote })
        for ((key, mine) in local) if (remote[key]?.sha == mine.sha) kept[key] = mine

        val result = when (decision) {
            Decision.UpToDate -> CloudSaveRun.upToDate()
            is Decision.Download -> {
                val snapshot = manifest ?: error("Epic lists no save to download")
                val chunks = HashMap<String, ByteArray>()
                var done = 0
                var removed = 0
                var failed = 0
                val byName = snapshot.fileManifestList?.elements.orEmpty().associateBy { it.filename.replace('\\', '/') }
                val chunkInfos = snapshot.chunkDataList?.elements.orEmpty().associateBy { it.guidStr }
                for (key in decision.fetch) {
                    val file = byName[key]
                    val target = CloudSaveRun.localFile(folders, key)
                    val ok = file != null && target != null && runCatchingCancellable {
                        rebuild(file, target, remote.getValue(key).timeMs) { guid ->
                            chunks.getOrPut(guid) {
                                val chunk = chunkInfos[guid] ?: error("Epic's save names a chunk it does not list")
                                val path = chunkPath(listing, latest!!.first, chunk) ?: error("Epic's save is missing a chunk")
                                readChunk(get(listing.getValue(path).readLink ?: error("Epic gave no link to a chunk")))
                            }
                        }
                    }.onFailure { Timber.tag(TAG).w(it, "Could not rebuild $key") }.getOrDefault(false)
                    if (ok) {
                        done++
                        kept[key] = Facts(remote.getValue(key).sha, target!!.length(), target.lastModified())
                    } else {
                        failed++
                    }
                }
                for (key in decision.removeLocal) {
                    if (files[key]?.delete() == true) {
                        removed++
                        kept.remove(key)
                    } else {
                        failed++
                    }
                }
                CloudSaveRun.downloaded(LABEL, done, removed, failed, decision.fetch.size + decision.removeLocal.size)
            }
            is Decision.Upload -> {
                // A snapshot holds every save file, so what this device has now is what goes up.
                val packed = pack(files, game, credentials.accountId, Instant.now())
                val sent = runCatchingCancellable { upload(base, ::authed, packed) }
                    .onFailure { Timber.tag(TAG).w(it, "Could not send the saves of ${game.appName}") }.getOrDefault(false)
                if (sent) {
                    kept.clear()
                    kept.putAll(local)
                    CloudSaveRun.uploaded(LABEL, decision.send.size, decision.removeRemote.size, 0, decision.send.size + decision.removeRemote.size)
                } else {
                    CloudSaveRun.uploaded(LABEL, 0, 0, decision.send.size + decision.removeRemote.size, decision.send.size + decision.removeRemote.size)
                }
            }
            is Decision.Conflict -> error("A conflict is settled before it is applied")
        }
        CloudSaveState.save(stateFile, CloudSaveState(kept))
        result
    }

    /** Epic's time ("2026-04-02T20:34:00.000Z") in epoch ms; 0 when it is none. Pure. */
    fun parseTime(text: String): Long = runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)

    private fun get(url: String): ByteArray = StoreHttp.http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
        check(response.isSuccessful) { "Epic would not send a save file (HTTP ${response.code})" }
        response.body?.bytes() ?: error("Epic sent an empty save file")
    }

    /** Writes [file] from its chunk parts over [target] through a file beside it; false when the SHA-1 does not match. */
    private fun rebuild(file: FileManifest, target: File, timeMs: Long, chunk: (String) -> ByteArray): Boolean {
        target.parentFile?.mkdirs()
        val temp = CloudSaveRun.tempFor(target)
        try {
            val digest = MessageDigest.getInstance("SHA-1")
            temp.outputStream().use { out ->
                for (part in file.chunkParts) {
                    val data = chunk(part.guidStr)
                    out.write(data, part.offset, part.size)
                    digest.update(data, part.offset, part.size)
                }
            }
            if (!digest.digest().contentEquals(file.hash)) return false
            return CloudSaveRun.replace(temp, target, timeMs)
        } finally {
            temp.delete()
        }
    }

    /** A snapshot ready to send: the chunks by their path and the manifest's path and bytes. */
    class Packed(val chunks: Map<String, ByteArray>, val manifestPath: String, val manifest: ByteArray)

    /**
     * [files] (by the cloud's name) as one snapshot: their bytes laid end to end in 1 MiB chunks (the last one padded,
     * as Legendary does), each chunk with its rolling hash, SHA-1 and group, and a manifest naming every file's parts.
     */
    fun pack(files: Map<String, File>, game: EpicGame, accountId: String, now: Instant, random: SecureRandom = SecureRandom()): Packed {
        val stamp = DateTimeFormatter.ofPattern("yyyy.MM.dd-HH.mm.ss").withZone(ZoneOffset.UTC).format(now)
        val chunks = LinkedHashMap<String, ByteArray>()
        val infos = mutableListOf<ChunkInfo>()
        val manifests = mutableListOf<FileManifest>()
        val buffer = ByteArrayOutputStream(CHUNK_SIZE)
        var guid = IntArray(4) { random.nextInt() }

        fun finish() {
            val info = chunkInfo(buffer.toByteArray(), guid)
            chunks[info.first.getPath()] = info.second
            infos += info.first
            buffer.reset()
            guid = IntArray(4) { random.nextInt() }
        }

        for ((name, file) in files.entries.sortedBy { it.key.lowercase() }) {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) continue
            val entry = FileManifest(filename = name, hash = MessageDigest.getInstance("SHA-1").digest(bytes), fileSize = bytes.size.toLong())
            var offset = 0
            while (offset < bytes.size) {
                if (buffer.size() >= CHUNK_SIZE) finish()
                val take = minOf(CHUNK_SIZE - buffer.size(), bytes.size - offset)
                entry.chunkParts.add(ChunkPart(guid = guid, offset = buffer.size(), size = take, fileOffset = offset.toLong()))
                buffer.write(bytes, offset, take)
                offset += take
            }
            manifests += entry
        }
        if (buffer.size() > 0) finish()

        val manifest = BinaryManifest().apply {
            meta = ManifestMeta(appName = "${game.appName}$accountId", buildVersion = stamp)
            customFields = CustomFields().also { it["CloudSaveFolder"] = game.saveFolder }
            chunkDataList = ChunkDataList().also { it.elements.addAll(infos) }
            fileManifestList = FileManifestList().also { it.elements.addAll(manifests) }
        }
        return Packed(chunks, "manifests/$stamp.manifest", manifest.serialize())
    }

    /** One chunk's entry and its file: padded to 1 MiB, hashed, its group from the GUID's CRC-32 (Legendary's rule). */
    private fun chunkInfo(data: ByteArray, guid: IntArray): Pair<ChunkInfo, ByteArray> {
        val padded = if (data.size < CHUNK_SIZE) data.copyOf(CHUNK_SIZE) else data
        val sha = MessageDigest.getInstance("SHA-1").digest(padded)
        val rolling = rollingHash(padded)
        val guidBytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).also { b -> guid.forEach { b.putInt(it) } }.array()
        val group = (CRC32().also { it.update(guidBytes) }.value % 100).toInt()
        val bytes = writeChunk(padded, guid, rolling, sha)
        val info = ChunkInfo(guid = guid, hash = rolling, shaHash = sha, groupNum = group, windowSize = padded.size, fileSize = bytes.size.toLong())
        return info to bytes
    }

    /** A chunk file: the 66-byte version-3 header and the zlib payload. */
    fun writeChunk(data: ByteArray, guid: IntArray, rolling: ULong, sha: ByteArray): ByteArray {
        val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(data) } }.toByteArray()
        val header = 66
        val buffer = ByteBuffer.allocate(header + compressed.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CHUNK_MAGIC)
        buffer.putInt(3)
        buffer.putInt(header)
        buffer.putInt(compressed.size)
        guid.forEach { buffer.putInt(it) }
        buffer.putLong(rolling.toLong())
        buffer.put(1.toByte()) // stored compressed
        buffer.put(sha)
        buffer.put(3.toByte()) // both hashes
        buffer.putInt(data.size)
        buffer.put(compressed)
        return buffer.array()
    }

    /** A chunk file's data: the payload after the header it names, inflated when stored compressed. */
    fun readChunk(bytes: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(buffer.int == CHUNK_MAGIC) { "Epic sent a save chunk this device cannot read" }
        buffer.int // header version
        val header = buffer.int
        buffer.int // compressed size
        buffer.position(buffer.position() + 24) // guid, rolling hash
        val compressed = (buffer.get().toInt() and 1) != 0
        val payload = bytes.copyOfRange(header, bytes.size)
        return if (compressed) InflaterInputStream(payload.inputStream()).use { it.readBytes() } else payload
    }

    private val ROLLING_TABLE: LongArray = LongArray(256) { seed ->
        var v = seed.toULong()
        repeat(8) { v = if ((v and 1uL) != 0uL) (v shr 1) xor 0xC96C5795D7870F42uL else v shr 1 }
        v.toLong()
    }

    /** Epic's rolling hash of a chunk (Legendary's `get_hash`). */
    fun rollingHash(data: ByteArray): ULong {
        var h = 0uL
        for (byte in data) h = ((h shl 1) or (h shr 63)) xor ROLLING_TABLE[byte.toInt() and 0xFF].toULong()
        return h
    }

    /** Asks Epic where to write each file of [packed], sends the chunks, then the manifest last; false when any failed. */
    private fun upload(base: String, authed: (String) -> Request.Builder, packed: Packed): Boolean {
        val names = packed.chunks.keys + packed.manifestPath
        val body = JSONObject().put("files", JSONArray(names.toList())).toString()
        val links = StoreHttp.http.newCall(authed(base).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            check(response.isSuccessful) { "Epic would not take the saves (HTTP ${response.code})" }
            val files = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" }).optJSONObject("files") ?: JSONObject()
            files.keys().asSequence().associateWith { files.getJSONObject(it).optString("writeLink", "") }.filterValues { it.isNotEmpty() }
        }
        fun link(name: String) = links[name] ?: links.entries.firstOrNull { it.key.endsWith("/$name") }?.value
        fun put(name: String, bytes: ByteArray): Boolean {
            val url = link(name) ?: return false
            val request = Request.Builder().url(url).put(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
            return StoreHttp.http.newCall(request).execute().use { it.isSuccessful }
        }
        if (!packed.chunks.all { (name, bytes) -> put(name, bytes) }) return false
        return put(packed.manifestPath, packed.manifest)
    }
}
