package dev.droidtop.pluginhost

import android.content.Context
import java.io.File
import java.net.URI
import dev.droidtop.library.settings.LibraryPaths
import dev.droidtop.library.settings.PathChange
import dev.droidtop.runtime.util.Sha256
import java.util.concurrent.ConcurrentHashMap
import dev.droidtop.net.Http
import dev.droidtop.net.ResumableDownload
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

/** What a finished download hands to its post-processing step; the step that moves, unpacks or installs it. */
fun interface DownloadPost {
    /** Returns the one-line outcome the jobs list shows. Throws with a sentence a person reads to fail the job. */
    suspend fun process(context: Context, file: File, args: Map<String, String>): String
}

/**
 * The one runner for a single-file HTTP(S) download (docs/SPEC.md 12a "Downloads", Droidtop/tracker#181):
 * droidtop's own downloader ([ResumableDownload], the one that store installs' sibling jobs share the policy with)
 * does the transfer into a `.part` file and carries on from it with a Range request, so the job is a native job in
 * the one [PluginJobsCenter] list that can Pause and Resume, and survives a lost connection, a network change or
 * a restart. Callers register a post-processing step under a name ([registerPost]) and run a job with [run].
 */
object DownloadJobs {
    const val KIND = "download"
    private const val OWNER = "Downloads"

    /** The post step that leaves the verified file where it landed; the caller takes it from [fileFor]. */
    const val POST_KEEP = "keep"
    const val POST_PLACE_IN_FOLDER = "place_in_folder"

    const val DIGEST_MISMATCH = "the downloaded file does not match its published digest"

    private const val ARG_URL = "url"
    private const val ARG_NAME = "name"
    private const val ARG_TITLE = "title"
    private const val ARG_POST = "post"
    private const val ARG_SHA256 = "sha256"
    private const val ARG_SHA1 = "sha1"
    private const val ARG_MD5 = "md5"
    private const val ARG_MAX_BYTES = "maxBytes"
    private const val ARG_HEADERS = "headers"

    private val posts = ConcurrentHashMap<String, DownloadPost>().apply { put(POST_KEEP, DownloadPost { _, _, _ -> "Downloaded" }) }

    /**
     * Credential headers are never written to the persisted job arguments (they would sit in
     * plain text in the jobs store); they live here for the process, keyed by the file name. After a
     * restart a download Android no longer has is queued again without them and fails clearly.
     */
    private val secretHeaders = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * :app sets this at start: what to do with a file a download placed in a game folder, given the job's arguments,
     * before the library is told about it. The library lives above this module, so the engine hint of a source's acquire
     * reply (docs/plugin-api.md 1.6, `engine`) is applied there (`AcquireEngineHint` in :library-core).
     */
    @Volatile var onPlaced: (context: Context, placed: File, args: Map<String, String>) -> Unit = { _, _, _ -> }

    /** Registers the runner with the one jobs registry. Called once at process start, before jobs are restored. */
    fun register(context: Context) {
        val appContext = context.applicationContext
        registerPost(POST_PLACE_IN_FOLDER) { jobContext, file, args ->
            val target = withContext(Dispatchers.IO) {
                placeInFolder(file, args).also {
                    // What the job carries about the placed file beyond its bytes (a source's engine hint), applied
                    // before the library looks at it, so the first index already reads it.
                    runCatching { onPlaced(jobContext, it, args) }
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
            download = true,
        ) { args, checkpoint, report -> execute(appContext, args, checkpoint, report) }
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
        /** Digests a source that publishes no SHA-256 gives (the Internet Archive lists MD5 and SHA-1); the strongest one given is checked. */
        sha1: String? = null,
        md5: String? = null,
        maxBytes: Long = 0L,
        headers: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
        /** The file's size when the caller knows it: the download policy lets a small one use mobile data. */
        sizeBytes: Long = 0L,
        /** True for a download nobody just asked for: the policy's update window and "while playing" apply to it too. */
        automatic: Boolean = false,
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
            sha1?.let { put(ARG_SHA1, it) }
            md5?.let { put(ARG_MD5, it) }
            if (maxBytes > 0) put(ARG_MAX_BYTES, maxBytes.toString())
            if (plain.isNotEmpty()) put(ARG_HEADERS, JSONObject(plain.associate { it.key to it.value }).toString())
            if (sizeBytes > 0) put(DownloadGate.ARG_BYTES, sizeBytes.toString())
            if (automatic) put(DownloadGate.ARG_AUTOMATIC, "1")
        }
        val finished = CompletableDeferred<PluginResult>()
        val jobId = PluginJobsCenter.startNative(
            context, KIND, title, args, onComplete = { finished.complete(it) }, owner = OWNER,
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

    /** The checkpoint once every byte is in the file: a restart then goes on to the post step and never downloads again. */
    private const val FETCHED = "fetched"
    private const val REPORT_EVERY_NS = 1_000_000_000L

    private fun cancelDownload(context: Context, args: Map<String, String>, checkpoint: String?) {
        args[ARG_NAME]?.let { name ->
            runCatching {
                val file = fileFor(context, name)
                file.delete()
                ResumableDownload.discard(file)
            }
        }
    }

    internal suspend fun execute(
        context: Context,
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
        val url = requireNotNull(args[ARG_URL]) { "the download has no address" }
        val file = fileFor(context, name)
        placedBeforeTheProcessEnded(file, postName, args, checkpoint) { LibraryPaths.report(context, PathChange.added(it)) }?.let { return it }
        if (!(checkpoint == FETCHED && file.isFile)) {
            withContext(Dispatchers.IO) { fetch(url, headers, file, args[ARG_MAX_BYTES]?.toLongOrNull() ?: 0L, report) }
            report(100, "Checking the download…", FETCHED)
        }
        verifyDigest(file, args)
        report(100, "Finishing…", null)
        val summary = post.process(context, file, args)
        if (postName != POST_KEEP) withContext(Dispatchers.IO) { file.delete() }
        return summary
    }

    /**
     * A restored job whose file the previous run had already placed in the game folder, when the process ended
     * after the rename and before the job was marked done: the checkpoint says every byte had arrived, the
     * downloads area no longer holds the file, and the game folder does. The job is finished here and the placed
     * file reported again (a report is idempotent), so the game is not lost to a download that starts over.
     * Null when this is not that case.
     */
    internal suspend fun placedBeforeTheProcessEnded(
        file: File,
        postName: String,
        args: Map<String, String>,
        checkpoint: String?,
        reportPlaced: (File) -> Unit,
    ): String? {
        if (postName != POST_PLACE_IN_FOLDER || checkpoint != FETCHED) return null
        return withContext(Dispatchers.IO) {
            val target = File(args["destinationPath"] ?: return@withContext null, args["targetName"] ?: return@withContext null)
            if (file.exists() || !target.isFile) return@withContext null
            reportPlaced(target)
            "Added ${target.name}"
        }
    }

    /**
     * Gets [url] downloaded into [destination] with [ResumableDownload]: a partial file from an earlier run, a
     * pause or a lost connection carries on with a Range request, and a server that cannot resume starts the file
     * again, which the status line says. Progress is reported at most once a second. Pause and Cancel cancel the
     * coroutine, which closes the connection at once. Blocking: callers run it on the IO dispatcher.
     */
    internal suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        destination: File,
        maxBytes: Long,
        report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
        retrySleep: ((Long) -> Unit)? = null,
    ) {
        val handle = ResumableDownload.Handle()
        var lastReport = 0L
        try {
            coroutineScope {
                // The transfer blocks its thread: this watcher closes the connection the moment the job is cancelled.
                val watcher = launch(Dispatchers.Default) {
                    try {
                        awaitCancellation()
                    } finally {
                        handle.cancel()
                    }
                }
                try {
                    ResumableDownload.fetch(
                        url, destination, headers, maxBytes = maxBytes, handle = handle, sleep = retrySleep,
                        waiting = { report(-1, it, null) },
                        onProgress = { p ->
                            val now = System.nanoTime()
                            if (now - lastReport >= REPORT_EVERY_NS || (p.total > 0 && p.bytes == p.total)) {
                                lastReport = now
                                report(if (p.total > 0) (p.bytes * 100 / p.total).toInt().coerceIn(0, 100) else -1, downloadingLine(p), null)
                            }
                        },
                    )
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: ResumableDownload.TooLargeException) {
            throw IllegalStateException(e.message)
        } catch (e: Http.HttpException) {
            throw IllegalStateException("the server answered HTTP ${e.status}")
        } catch (e: IOException) {
            // Pause and Cancel close the connection under the transfer: that is a cancellation, not a failure.
            coroutineContext.ensureActive()
            throw IllegalStateException("the download failed: ${e.message}")
        }
    }

    internal fun downloadingLine(p: ResumableDownload.Progress): String {
        val sizes = if (p.total > 0) "${p.bytes / 1024 / 1024} MB of ${p.total / 1024 / 1024} MB" else "${p.bytes / 1024 / 1024} MB"
        return (if (p.restarted) "Started again: the server cannot resume. " else "") + "Downloading… $sizes"
    }

    /** Checks [file] against the strongest digest [args] carry; a mismatch deletes the file and fails the job with a plain sentence. */
    internal suspend fun verifyDigest(file: File, args: Map<String, String>) {
        val (algorithm, expected) = strongestDigest(args[ARG_SHA256], args[ARG_SHA1], args[ARG_MD5]) ?: return
        val actual = withContext(Dispatchers.IO) { digestHex(file, algorithm) }
        if (!actual.equals(expected, ignoreCase = true)) {
            withContext(Dispatchers.IO) { file.delete() }
            throw IllegalStateException(DIGEST_MISMATCH)
        }
    }

    /** The java.security algorithm and the expected hex of the strongest digest given (SHA-256, then SHA-1, then MD5); null when none is. */
    internal fun strongestDigest(sha256: String?, sha1: String?, md5: String?): Pair<String, String>? = when {
        sha256 != null -> "SHA-256" to sha256
        sha1 != null -> "SHA-1" to sha1
        md5 != null -> "MD5" to md5
        else -> null
    }

    internal fun digestHex(file: File, algorithm: String): String {
        if (algorithm == "SHA-256") return Sha256.hex(file)
        val digest = java.security.MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Additive contract-2 acquire result field, kept separate from the plugin's own job implementation. */
data class AcquireDownloadDescriptor(
    val url: String,
    val headers: Map<String, String>,
    val fileName: String,
    val sha256: String?,
    val size: Long?,
    /** SHA-1 and MD5 for sources that publish no SHA-256; when several digests are given the strongest is checked. */
    val sha1: String? = null,
    val md5: String? = null,
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
            val sha1 = value.optString("sha1").takeIf { it.isNotEmpty() }
            require(sha1 == null || sha1.matches(Regex("[A-Fa-f0-9]{40}")))
            val md5 = value.optString("md5").takeIf { it.isNotEmpty() }
            require(md5 == null || md5.matches(Regex("[A-Fa-f0-9]{32}")))
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
            AcquireDownloadDescriptor(url, headers, fileName, digest, size, sha1, md5, session)
        }.getOrNull()
    }
}
