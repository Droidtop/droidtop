package dev.droidtop.pluginhost

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.net.URI
import dev.droidtop.library.settings.LibraryPaths
import dev.droidtop.library.settings.PathChange
import dev.droidtop.runtime.util.Sha256
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One request to fetch one file. [name] is a bare file name: the file lands in droidtop's own
 * external files directory (`downloads/<name>`, scoped storage needs nothing more) and the job's
 * post-processing step moves, unpacks or installs it from there.
 */
data class DownloadRequest(
    val url: String,
    val title: String,
    val name: String,
    val headers: Map<String, String> = emptyMap(),
)

/** What DownloadManager's cursor says about one download, as plain values. */
data class DownloadSnapshot(
    val status: Int,
    val reason: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

/** The job-level meaning of a [DownloadSnapshot] (docs/SPEC.md 12a "Downloads"). */
sealed interface DownloadState {
    /** Android has it queued or is waiting (network, Wi-Fi, a retry): the percent stays as it was. */
    data class Waiting(val line: String) : DownloadState

    /** Bytes are coming. [percent] is -1 while the server has not said how long the file is. */
    data class Running(val percent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadState

    object Succeeded : DownloadState

    data class Failed(val message: String) : DownloadState

    companion object {
        /** DownloadManager status and reason to a job state. The one place that knows the numbers. */
        fun of(snapshot: DownloadSnapshot): DownloadState = when (snapshot.status) {
            DownloadManager.STATUS_PENDING -> Waiting("Waiting to start")
            DownloadManager.STATUS_RUNNING -> Running(
                percent = if (snapshot.totalBytes > 0) (snapshot.downloadedBytes * 100 / snapshot.totalBytes).toInt().coerceIn(0, 100) else -1,
                downloadedBytes = snapshot.downloadedBytes,
                totalBytes = snapshot.totalBytes,
            )
            DownloadManager.STATUS_PAUSED -> Waiting(
                when (snapshot.reason) {
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a network"
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
                    DownloadManager.PAUSED_WAITING_TO_RETRY -> "Waiting to retry"
                    else -> "Paused by Android"
                },
            )
            DownloadManager.STATUS_SUCCESSFUL -> Succeeded
            DownloadManager.STATUS_FAILED -> Failed(failureText(snapshot.reason))
            else -> Waiting("Waiting")
        }

        /** Words for DownloadManager's error reasons: an HTTP status code, or one of its own ERROR_ constants. */
        fun failureText(reason: Int): String = when {
            reason in 100..599 -> "the server answered HTTP $reason"
            reason == DownloadManager.ERROR_INSUFFICIENT_SPACE -> "there is not enough free space"
            reason == DownloadManager.ERROR_DEVICE_NOT_FOUND -> "the storage is not available"
            reason == DownloadManager.ERROR_CANNOT_RESUME -> "the download could not be resumed"
            reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "the file already exists"
            reason == DownloadManager.ERROR_FILE_ERROR -> "the file could not be written"
            reason == DownloadManager.ERROR_HTTP_DATA_ERROR -> "the connection broke"
            reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "the server redirected too many times"
            reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "the server answered with something Android cannot use"
            else -> "the download failed (code $reason)"
        }
    }
}

/** The part of Android's DownloadManager the runner needs; a test swaps it for a fake. */
interface DownloadBackend {
    /** Queues [request] into [destination] and returns its download id. */
    fun enqueue(request: DownloadRequest, destination: File): Long

    /** The download's state, or null when Android no longer has it (removed from the system's list). */
    fun query(id: Long): DownloadSnapshot?

    /** Removes the entry and any partial or finished file it holds. */
    fun remove(id: Long)
}

class SystemDownloadBackend(context: Context) : DownloadBackend {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DownloadManager::class.java)

    override fun enqueue(request: DownloadRequest, destination: File): Long {
        val root = requireNotNull(appContext.getExternalFilesDir(null)) { "the storage is not available" }
        val queued = DownloadManager.Request(Uri.parse(request.url))
            .setTitle(request.title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, null, destination.relativeTo(root).path)
            .addRequestHeader("User-Agent", "droidtop")
        request.headers.forEach { (key, value) -> queued.addRequestHeader(key, value) }
        return manager.enqueue(queued)
    }

    override fun query(id: Long): DownloadSnapshot? =
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun int(column: String) = cursor.getInt(cursor.getColumnIndexOrThrow(column))
            fun long(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            DownloadSnapshot(
                status = int(DownloadManager.COLUMN_STATUS),
                reason = int(DownloadManager.COLUMN_REASON),
                downloadedBytes = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                totalBytes = long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
        }

    override fun remove(id: Long) {
        manager.remove(id)
    }
}

/** What a finished download hands to its post-processing step; the step that moves, unpacks or installs it. */
fun interface DownloadPost {
    /** Returns the one-line outcome the jobs list shows. Throws with a sentence a person reads to fail the job. */
    suspend fun process(context: Context, file: File, args: Map<String, String>): String
}

/**
 * The one runner for a single-file HTTP(S) download (docs/SPEC.md 12a "Downloads", Droidtop/tracker#181):
 * Android's DownloadManager does the transfer (its own notification, its own resume after a lost
 * connection), the job is a native job in the one [PluginJobsCenter] list whose checkpoint is the
 * DownloadManager id, so a restart finds the same download again. Callers register a post-processing
 * step under a name ([registerPost]) and run a job with [run].
 */
object DownloadJobs {
    const val KIND = "download"
    private const val OWNER = "Downloads"
    private const val POLL_MS = 1_000L

    /** The post step that leaves the verified file where it landed; the caller takes it from [fileFor]. */
    const val POST_KEEP = "keep"
    const val POST_PLACE_IN_FOLDER = "place_in_folder"

    const val DIGEST_MISMATCH = "the downloaded file does not match its published digest"

    private const val ARG_URL = "url"
    private const val ARG_NAME = "name"
    private const val ARG_TITLE = "title"
    private const val ARG_POST = "post"
    private const val ARG_SHA256 = "sha256"
    private const val ARG_MAX_BYTES = "maxBytes"
    private const val ARG_HEADERS = "headers"

    private val posts = ConcurrentHashMap<String, DownloadPost>().apply { put(POST_KEEP, DownloadPost { _, _, _ -> "Downloaded" }) }

    /**
     * Credential headers are never written to the persisted job arguments (they would sit in
     * plain text in the jobs store); they live here for the process, keyed by the file name. After a
     * restart a download Android no longer has is queued again without them and fails clearly.
     */
    private val secretHeaders = ConcurrentHashMap<String, Map<String, String>>()

    internal var backendFactory: (Context) -> DownloadBackend = { SystemDownloadBackend(it) }

    /** Registers the runner with the one jobs registry. Called once at process start, before jobs are restored. */
    fun register(context: Context) {
        val appContext = context.applicationContext
        registerPost(POST_PLACE_IN_FOLDER) { jobContext, file, args ->
            val target = withContext(Dispatchers.IO) {
                placeInFolder(file, args).also {
                    // The file is the library's from here, said by the job and not by whatever screen started it, so
                    // a page that was closed, or a restart, loses nothing (docs/SPEC.md 7g, "Targeted indexing").
                    LibraryPaths.report(jobContext, PathChange.added(it))
                }
            }
            "Added ${target.name}"
        }
        FlutterRuntimeManager.registerDownloadPost()
        PythonRuntimeManager.registerDownloadPost()
        PluginJobsCenter.registerNative(
            KIND,
            onCancel = { args, checkpoint -> cancelDownload(appContext, args, checkpoint) },
            reattachOnRestart = true,
        ) { args, checkpoint, report -> execute(appContext, backendFactory(appContext), args, checkpoint, report) }
    }

    /** Moves [file] to `targetName` in `destinationPath`; the one place a download is placed in a game folder. Returns where it went. */
    internal fun placeInFolder(file: File, args: Map<String, String>): File {
        val destination = File(requireNotNull(args["destinationPath"]) { "the game folder is missing" })
        require(destination.isDirectory || destination.mkdirs()) { "the game folder is not available" }
        val target = File(destination, requireNotNull(args["targetName"]) { "the file name is missing" })
        require(!target.exists()) { "a file with that name already exists" }
        require(file.renameTo(target)) { "the download could not be placed in the game folder" }
        return target
    }

    /** Names the post-processing step a job's `post` argument refers to. Registered at process start, so a restored job finds it. */
    fun registerPost(name: String, post: DownloadPost) {
        posts[name] = post
    }

    /** Where a download named [name] lands: droidtop's own external files directory. */
    fun fileFor(context: Context, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "a download is named with a bare file name" }
        val root = context.getExternalFilesDir(null) ?: File(context.filesDir, "external-unavailable")
        return File(root, "downloads/$name")
    }

    /**
     * Downloads [url] as a job and waits for its post step ([post], a name from [registerPost]) to
     * finish, narrating the job's status through [onStatus]. [extra] reaches the post step unchanged.
     * The job outlives a cancelled caller: it is in "Downloads and installs" and finishes there.
     */
    suspend fun run(
        context: Context,
        title: String,
        post: String,
        url: String,
        name: String,
        sha256: String? = null,
        maxBytes: Long = 0L,
        headers: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
        onStatus: (String) -> Unit = {},
    ): PluginResult {
        val (secret, plain) = headers.entries.partition { entry ->
            listOf("authorization", "proxy-authorization", "cookie", "credential", "token", "api-key", "secret")
                .any { marker -> entry.key.contains(marker, ignoreCase = true) }
        }
        if (secret.isNotEmpty()) secretHeaders[name] = secret.associate { it.key to it.value }
        val args = buildMap {
            putAll(extra)
            put(ARG_URL, url)
            put(ARG_NAME, name)
            put(ARG_TITLE, title)
            put(ARG_POST, post)
            sha256?.let { put(ARG_SHA256, it) }
            if (maxBytes > 0) put(ARG_MAX_BYTES, maxBytes.toString())
            if (plain.isNotEmpty()) put(ARG_HEADERS, JSONObject(plain.associate { it.key to it.value }).toString())
        }
        val finished = CompletableDeferred<PluginResult>()
        val jobId = PluginJobsCenter.startNative(
            context, KIND, title, args, onComplete = { finished.complete(it) }, owner = OWNER, pausable = false,
        ) ?: return PluginResult.failure("downloads are not available in this build")
        return coroutineScope {
            val narrator = launch {
                PluginJobsCenter.entries().collect { list ->
                    list.firstOrNull { it.jobId == jobId }?.takeIf { !it.done }?.let { entry ->
                        onStatus(if (entry.percent in 0..99) "${entry.statusLine} (${entry.percent}%)" else entry.statusLine)
                    }
                }
            }
            try {
                finished.await()
            } finally {
                narrator.cancel()
                secretHeaders.remove(name)
            }
        }
    }

    private fun cancelDownload(context: Context, args: Map<String, String>, checkpoint: String?) {
        checkpoint?.toLongOrNull()?.let { runCatching { backendFactory(context).remove(it) } }
        args[ARG_NAME]?.let { name -> runCatching { fileFor(context, name).delete() } }
    }

    internal suspend fun execute(
        context: Context,
        backend: DownloadBackend,
        args: Map<String, String>,
        checkpoint: String?,
        report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
    ): String {
        val name = requireNotNull(args[ARG_NAME]) { "the download has no file name" }
        val postName = args[ARG_POST] ?: POST_KEEP
        val post = posts[postName] ?: throw IllegalStateException("this version of droidtop cannot finish that download")
        val headers = buildMap {
            args[ARG_HEADERS]?.let { raw -> JSONObject(raw).let { o -> o.keys().forEach { put(it, o.getString(it)) } } }
            secretHeaders[name]?.let { putAll(it) }
        }
        val request = DownloadRequest(requireNotNull(args[ARG_URL]) { "the download has no address" }, args[ARG_TITLE] ?: name, name, headers)
        val file = fileFor(context, name)
        placedBeforeTheProcessEnded(backend, file, postName, args, checkpoint) { LibraryPaths.report(context, PathChange.added(it)) }?.let { return it }
        val id = withContext(Dispatchers.IO) {
            fetch(backend, request, file, checkpoint, args[ARG_MAX_BYTES]?.toLongOrNull() ?: 0L, report)
        }
        args[ARG_SHA256]?.let { expected ->
            report(100, "Checking the download…", null)
            val actual = withContext(Dispatchers.IO) { sha256(file) }
            if (!actual.equals(expected, ignoreCase = true)) {
                withContext(Dispatchers.IO) { backend.remove(id) }
                throw IllegalStateException(DIGEST_MISMATCH)
            }
        }
        report(100, "Finishing…", null)
        val summary = post.process(context, file, args)
        if (postName != POST_KEEP) {
            withContext(Dispatchers.IO) {
                backend.remove(id)
                file.delete()
            }
        }
        return summary
    }

    /**
     * A restored job whose file the previous run had already placed in the game folder, when the process ended
     * after the rename and before the job was marked done: Android still holds the finished entry but the file
     * it points at has moved. The job is finished here, the entry released, and the placed file reported again
     * (a report is idempotent), so the game is not lost to "the download finished but its file is missing".
     * Null when this is not that case.
     */
    internal suspend fun placedBeforeTheProcessEnded(
        backend: DownloadBackend,
        file: File,
        postName: String,
        args: Map<String, String>,
        checkpoint: String?,
        reportPlaced: (File) -> Unit,
    ): String? {
        if (postName != POST_PLACE_IN_FOLDER || checkpoint == null) return null
        return withContext(Dispatchers.IO) {
            val id = checkpoint.toLongOrNull() ?: return@withContext null
            val target = File(args["destinationPath"] ?: return@withContext null, args["targetName"] ?: return@withContext null)
            val finished = backend.query(id)?.status == DownloadManager.STATUS_SUCCESSFUL
            if (!finished || file.exists() || !target.isFile) return@withContext null
            runCatching { backend.remove(id) }
            reportPlaced(target)
            "Added ${target.name}"
        }
    }

    /**
     * Gets [request] downloaded into [destination]: re-attaches to the DownloadManager entry named by
     * [checkpoint] when Android still has it, otherwise queues it afresh and reports the new id as the
     * checkpoint before anything else, so a restart cannot lose it. Polls the cursor every [pollMs]
     * (callers run this on IO) until the entry succeeds or fails. Returns the download id.
     */
    internal suspend fun fetch(
        backend: DownloadBackend,
        request: DownloadRequest,
        destination: File,
        checkpoint: String?,
        maxBytes: Long,
        report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
        pollMs: Long = POLL_MS,
    ): Long {
        val attached = checkpoint?.toLongOrNull()?.takeIf { backend.query(it) != null }
        val id = attached ?: run {
            // Android never overwrites: it would pick a second name and the job would read the old file.
            destination.parentFile?.mkdirs()
            if (destination.exists()) destination.delete()
            backend.enqueue(request, destination).also { report(-1, "Starting…", it.toString()) }
        }
        while (true) {
            val snapshot = backend.query(id) ?: throw IllegalStateException("the download was removed from Android's downloads")
            when (val state = DownloadState.of(snapshot)) {
                is DownloadState.Waiting -> report(-1, state.line, null)
                is DownloadState.Running -> {
                    if (maxBytes > 0 && state.totalBytes > maxBytes) {
                        backend.remove(id)
                        throw IllegalStateException("the file is larger than the ${maxBytes / 1024 / 1024} MiB cap")
                    }
                    report(state.percent, downloadingLine(state), null)
                }
                DownloadState.Succeeded -> {
                    if (!destination.isFile) throw IllegalStateException("the download finished but its file is missing")
                    if (maxBytes > 0 && destination.length() > maxBytes) {
                        backend.remove(id)
                        throw IllegalStateException("the file is larger than the ${maxBytes / 1024 / 1024} MiB cap")
                    }
                    return id
                }
                is DownloadState.Failed -> {
                    backend.remove(id)
                    destination.delete()
                    throw IllegalStateException(state.message)
                }
            }
            delay(pollMs)
        }
    }

    private fun downloadingLine(state: DownloadState.Running): String =
        if (state.totalBytes > 0) "Downloading… ${state.downloadedBytes / 1024 / 1024} MB of ${state.totalBytes / 1024 / 1024} MB" else "Downloading… ${state.downloadedBytes / 1024 / 1024} MB"

    private fun sha256(file: File): String = Sha256.hex(file)
}

/** Additive contract-2 acquire result field, kept separate from the plugin's own job implementation. */
data class AcquireDownloadDescriptor(
    val url: String,
    val headers: Map<String, String>,
    val fileName: String,
    val sha256: String?,
    val size: Long?,
    /** A download a page started in the plugin's web session, named by the one-use token `web.session open_in_session` gave (docs/plugin-api.md 3 G3). */
    val session: String? = null,
) {
    companion object {
        fun parse(json: String?): AcquireDownloadDescriptor? = runCatching {
            val value = JSONObject(requireNotNull(json))
            val url = value.getString("url")
            val parsedUrl = URI(url)
            require(parsedUrl.scheme in setOf("http", "https") && !parsedUrl.host.isNullOrBlank())
            val fileName = value.getString("fileName")
            require(fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")))
            val digest = value.optString("sha256").takeIf { it.isNotEmpty() }
            require(digest == null || digest.matches(Regex("[A-Fa-f0-9]{64}")))
            val size = if (value.has("size") && !value.isNull("size")) value.getLong("size") else null
            require(size == null || size > 0)
            val headersJson = value.optJSONObject("headers")
            val headers = buildMap {
                headersJson?.keys()?.forEach { key ->
                    val headerValue = headersJson.getString(key)
                    require(key.isNotBlank() && headerValue.isNotBlank())
                    put(key, headerValue)
                }
            }
            val session = value.optString("session").takeIf { it.isNotEmpty() }
            require(session == null || session.matches(Regex("w-[0-9a-f-]{36}")))
            AcquireDownloadDescriptor(url, headers, fileName, digest, size, session)
        }.getOrNull()
    }
}
