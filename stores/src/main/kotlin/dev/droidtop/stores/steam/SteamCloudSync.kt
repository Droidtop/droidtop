package dev.droidtop.stores.steam

import dev.droidtop.library.stores.SaveChoice
import dev.droidtop.library.stores.SaveConflict
import dev.droidtop.library.stores.SaveConflictResolver
import dev.droidtop.library.stores.SaveSide
import dev.droidtop.library.stores.SaveSyncResult
import dev.droidtop.stores.steam.SteamCloudPlan.Decision
import dev.droidtop.stores.steam.SteamCloudPlan.Facts
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.FileDownloadInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Date
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import timber.log.Timber

/**
 * One Steam Cloud save sync of one game (docs/SPEC.md 7g, "Stores",
 * Droidtop/tracker#313): the file transfers of GameNative's SteamAutoCloud
 * (GPL-3.0: the list call, the per-file download URL, the upload batch with its
 * block requests), with the decision made by [SteamCloudPlan] from the state
 * kept at the last sync, so "this device changed" and "the cloud changed" are
 * known and not guessed from timestamps. Files are written beside their
 * target and checked against the SHA-1 Steam lists before they replace
 * anything.
 */
internal object SteamCloudSync {
    /** What came of a sync, for the caller's follow-up call to Steam. */
    class Outcome(val result: SaveSyncResult, val uploadsRequired: Boolean, val uploadsCompleted: Boolean, val settled: Boolean)

    private class Remote(val name: CloudKey.Parsed, val apiName: String, val facts: Facts)

    suspend fun run(
        filesDir: File,
        http: OkHttpClient,
        cloud: SteamCloud,
        app: SteamApp,
        layout: SaveLayout,
        clientId: Long,
        machineName: String,
        buildId: Long,
        title: String,
        onConflict: SaveConflictResolver?,
        progress: (String) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val list = cloud.getAppFileListChange(app.id, 0L).await()
        val remote = LinkedHashMap<String, Remote>()
        for (file in list.files) {
            if (file.persistState.name.contains("Deleted", ignoreCase = true)) continue
            val prefix = if (file.hasPathPrefixIndex && file.pathPrefixIndex in list.pathPrefixes.indices) list.pathPrefixes[file.pathPrefixIndex] else ""
            val name = CloudKey.parse(CloudKey.join(prefix, file.filename))
            // Other systems' roots are not in a Windows prefix; they are left alone.
            if (!name.root.isWindows) continue
            val apiName = if (prefix.isBlank()) file.filename else "$prefix/${file.filename}".replace(Regex("/+"), "/")
            remote[name.key] = Remote(name, apiName, Facts(hex(file.shaFile), file.rawFileSize.toLong(), file.timestamp.time))
        }

        val state = SteamCloudState.load(filesDir, app.id)
        val scanned = layout.scan(remote.values.map { it.name.name } + state.files.keys)
        val local = LinkedHashMap<String, SaveLayout.Local>()
        val localFacts = LinkedHashMap<String, Facts>()
        for (found in scanned) {
            val file = found.file
            val size = file.length()
            val modified = file.lastModified()
            val known = state.files[found.name.key]
            // A file whose size and time are those of the last sync is the file of the last sync.
            val sha = if (known != null && known.size == size && known.timeMs == modified) known.sha else sha1Hex(file)
            local[found.name.key] = found
            localFacts[found.name.key] = Facts(sha, size, modified)
        }
        val remoteFacts = remote.mapValues { it.value.facts }

        var decision = SteamCloudPlan.decide(state.shas, localFacts, remoteFacts)
        if (decision is Decision.Conflict) {
            val choice = onConflict?.resolve(title, SaveConflict(side(decision.local), side(decision.cloud), "Steam Cloud"))
                ?: return@withContext Outcome(
                    SaveSyncResult("Saves differ between this device and Steam Cloud. Choose which to keep", unresolved = true),
                    uploadsRequired = false, uploadsCompleted = false, settled = false,
                )
            // Treating one side as unchanged makes the other side win.
            decision = when (choice) {
                SaveChoice.LOCAL -> SteamCloudPlan.decide(remoteFacts.mapValues { it.value.sha }, localFacts, remoteFacts)
                SaveChoice.CLOUD -> SteamCloudPlan.decide(localFacts.mapValues { it.value.sha }, localFacts, remoteFacts)
            }
        }

        when (decision) {
            Decision.UpToDate -> {
                // Remember what both sides hold, so a change from here on is known to be a change.
                if (state.shas != localFacts.mapValues { it.value.sha }) SteamCloudState.save(filesDir, app.id, SteamCloudState(localFacts))
                Outcome(SaveSyncResult("Saves are up to date"), uploadsRequired = false, uploadsCompleted = true, settled = true)
            }
            is Decision.Download -> download(filesDir, http, cloud, app, layout, state, decision, remote, local, localFacts, progress)
            is Decision.Upload -> upload(filesDir, http, cloud, app, state, decision, remote, local, localFacts, clientId, machineName, buildId, progress)
            is Decision.Conflict -> error("A conflict is settled before it is applied")
        }
    }

    private fun side(side: SteamCloudPlan.Side) = SaveSide(side.timeMs, side.files, side.bytes)

    private suspend fun download(
        filesDir: File,
        http: OkHttpClient,
        cloud: SteamCloud,
        app: SteamApp,
        layout: SaveLayout,
        state: SteamCloudState,
        decision: Decision.Download,
        remote: Map<String, Remote>,
        local: Map<String, SaveLayout.Local>,
        localFacts: Map<String, Facts>,
        progress: (String) -> Unit,
    ): Outcome {
        val facts = HashMap(state.files.filterKeys { it in remote || it in localFacts })
        var downloaded = 0
        var removed = 0
        var failed = 0
        for ((index, key) in decision.fetch.withIndex()) {
            progress("Downloading saves ${index + 1}/${decision.fetch.size}")
            val file = remote.getValue(key)
            val target = layout.localFile(file.name)
            val written = target != null && runCatching { fetch(http, cloud, app.id, file, target) }
                .onFailure { Timber.tag(TAG).w(it, "Could not download ${file.apiName}") }.getOrDefault(false)
            if (written) {
                downloaded++
                facts[key] = Facts(file.facts.sha, target!!.length(), target.lastModified())
            } else {
                failed++
            }
        }
        for (key in decision.removeLocal) {
            val file = local[key]?.file ?: continue
            if (file.delete()) {
                removed++
                facts.remove(key)
            } else {
                failed++
            }
        }
        // Keys both sides already agreed on keep their entry (or gain one).
        for ((key, mine) in localFacts) if (remote[key]?.facts?.sha == mine.sha) facts[key] = mine
        finish(filesDir, app.id, facts)
        val line = if (failed > 0) "Cloud saves: $failed of ${decision.fetch.size + decision.removeLocal.size} files could not be updated" else "Saves updated from Steam Cloud ($downloaded)"
        return Outcome(SaveSyncResult(line, downloaded = downloaded, removed = removed, failed = failed > 0), uploadsRequired = false, uploadsCompleted = true, settled = failed == 0)
    }

    private suspend fun upload(
        filesDir: File,
        http: OkHttpClient,
        cloud: SteamCloud,
        app: SteamApp,
        state: SteamCloudState,
        decision: Decision.Upload,
        remote: Map<String, Remote>,
        local: Map<String, SaveLayout.Local>,
        localFacts: Map<String, Facts>,
        clientId: Long,
        machineName: String,
        buildId: Long,
        progress: (String) -> Unit,
    ): Outcome {
        val facts = HashMap(state.files.filterKeys { it in remote || it in localFacts })
        val names = decision.send.associateWith { key -> remote[key]?.apiName ?: local.getValue(key).name.name }
        val removeNames = decision.removeRemote.associateWith { key -> remote.getValue(key).apiName }
        val batch = cloud.beginAppUploadBatch(
            appId = app.id,
            machineName = machineName,
            filesToUpload = names.values.toList(),
            filesToDelete = removeNames.values.toList(),
            clientId = clientId,
            appBuildId = buildId,
        ).await()
        var uploaded = 0
        var removed = 0
        var failed = 0
        for ((index, key) in decision.send.withIndex()) {
            progress("Uploading saves ${index + 1}/${decision.send.size}")
            val file = local.getValue(key).file
            val mine = localFacts.getValue(key)
            val sent = runCatching { send(http, cloud, app.id, batch.batchID, names.getValue(key), file, mine) }
                .onFailure { Timber.tag(TAG).w(it, "Could not upload ${names.getValue(key)}") }.getOrDefault(false)
            if (sent) {
                uploaded++
                facts[key] = mine
            } else {
                failed++
            }
        }
        for ((key, name) in removeNames) {
            val gone = runCatching { cloud.deleteFile(app.id, name, batch.batchID).await() }
                .onFailure { Timber.tag(TAG).w(it, "Could not delete $name from the cloud") }.getOrDefault(false)
            if (gone) {
                removed++
                facts.remove(key)
            } else {
                failed++
            }
        }
        for ((key, mine) in localFacts) if (remote[key]?.facts?.sha == mine.sha) facts[key] = mine
        runCatching { cloud.completeAppUploadBatch(app.id, batch.batchID, if (failed == 0) EResult.OK else EResult.Fail).await() }
            .onFailure { Timber.tag(TAG).w(it, "Could not close the save upload batch") }
        finish(filesDir, app.id, facts)
        val line = if (failed > 0) "Cloud saves: $failed of ${decision.send.size + decision.removeRemote.size} files could not be sent" else "Saves sent to Steam Cloud ($uploaded)"
        return Outcome(SaveSyncResult(line, uploaded = uploaded, removed = removed, failed = failed > 0), uploadsRequired = true, uploadsCompleted = failed == 0, settled = failed == 0)
    }

    /** Remembers the files both sides now agree on; an entry the pass did not finish keeps its old SHA. */
    private fun finish(filesDir: File, appId: Int, facts: Map<String, Facts>) {
        SteamCloudState.save(filesDir, appId, SteamCloudState(facts))
    }

    /** Downloads [file] over [target]; false when Steam gave no URL, the transfer failed or the SHA-1 did not match. */
    private suspend fun fetch(http: OkHttpClient, cloud: SteamCloud, appId: Int, file: Remote, target: File): Boolean {
        val info = cloud.clientFileDownload(appId, file.apiName).await()
        if (info.urlHost.isEmpty()) return false
        val url = (if (info.useHttps) "https://" else "http://") + info.urlHost + info.urlPath
        val request = Request.Builder().url(url).headers(headersOf(info.requestHeaders.map { it.name to it.value })).build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null || target.isDirectory) return@use false
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, ".${target.name}.droidtop-cloud")
                try {
                    if (!copyBody(info, body, temp)) return@use false
                    val expected = hex(info.shaFile)
                    if (temp.length() != info.rawFileSize.toLong() || (expected.isNotEmpty() && sha1Hex(temp) != expected)) return@use false
                    temp.setLastModified(info.timestamp.time)
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        target.setLastModified(info.timestamp.time)
                    }
                    true
                } finally {
                    temp.delete()
                }
            }
        }
    }

    /** Writes the body to [temp]; Steam zips a file it compressed (one entry, when the sizes differ). */
    private fun copyBody(info: FileDownloadInfo, body: ResponseBody, temp: File): Boolean {
        temp.outputStream().use { out ->
            if (info.fileSize != info.rawFileSize) {
                ZipInputStream(body.byteStream()).use { zip ->
                    if (zip.nextEntry == null) return false
                    zip.copyTo(out)
                }
            } else {
                body.byteStream().use { it.copyTo(out) }
            }
        }
        return true
    }

    /** Uploads [file] as [name] in batch [batchId]; false when a block or the commit failed. */
    private suspend fun send(http: OkHttpClient, cloud: SteamCloud, appId: Int, batchId: Long, name: String, file: File, facts: Facts): Boolean {
        val size = facts.size.toInt()
        val sha = unhex(facts.sha)
        val info = cloud.beginFileUpload(
            appId = appId,
            fileSize = size,
            rawFileSize = size,
            fileSha = sha,
            timestamp = Date(facts.timeMs),
            filename = name,
            uploadBatchId = batchId,
        ).await()
        var ok = true
        withContext(Dispatchers.IO) {
            RandomAccessFile(file, "r").use { source ->
                for (block in info.blockRequests) {
                    val bytes = ByteArray(block.blockLength)
                    source.seek(block.blockOffset)
                    source.readFully(bytes)
                    val contentType = block.requestHeaders.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value
                    val request = Request.Builder()
                        .url((if (block.useHttps) "https://" else "http://") + block.urlHost + block.urlPath)
                        .put(bytes.toRequestBody((contentType ?: "application/octet-stream").toMediaTypeOrNull()))
                        .headers(headersOf(block.requestHeaders.map { it.name to it.value }))
                        .addHeader("Accept", "text/html,*/*;q=0.9")
                        .addHeader("accept-encoding", "gzip,identity,*;q=0")
                        .addHeader("accept-charset", "ISO-8859-1,utf-8,*;q=0.7")
                        .addHeader("user-agent", "Valve/Steam HTTP Client 1.0")
                        .build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.tag(TAG).w("Upload of %s failed: %s", name, response.message)
                            ok = false
                        }
                    }
                    if (!ok) break
                }
            }
        }
        val committed = cloud.commitFileUpload(transferSucceeded = ok, appId = appId, fileSha = sha, filename = name).await()
        return ok && committed
    }

    private fun headersOf(pairs: List<Pair<String, String>>): Headers =
        Headers.Builder().apply { pairs.forEach { (name, value) -> add(name, value) } }.build()

    fun sha1Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private const val TAG = "SteamCloudSync"
}
