package dev.droidtop.pluginhost

import android.content.Context
import java.io.File
import java.net.URI
import dev.droidtop.library.format.ValueFormat
import dev.droidtop.library.settings.LibraryPaths
import dev.droidtop.library.settings.PathChange
import dev.droidtop.runtime.util.ArchiveExtractor
import dev.droidtop.runtime.util.Sha256
import java.util.concurrent.ConcurrentHashMap
import dev.droidtop.net.DownloadCategory
import dev.droidtop.net.DownloadPools
import dev.droidtop.net.engine.EngineFetch
import dev.droidtop.net.engine.RedirectGuard
import dev.droidtop.net.engine.EngineHost
import dev.droidtop.net.engine.EnginePrefs
import dev.droidtop.net.engine.TransferException
import dev.droidtop.net.engine.TransferRequest
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject

/** What a finished download hands to its post-processing step; the step that moves, unpacks or installs it. */
fun interface DownloadPost {
    /** Returns the one-line outcome the jobs list shows. Throws with a sentence a person reads to fail the job. */
    suspend fun process(context: Context, file: File, args: Map<String, String>): String
}

/**
 * What a finished download with several files hands to its post-processing step: every file, in order, with the
 * job arguments of each (its own `name`, `targetName`, digests and headers over the arguments they share).
 */
fun interface DownloadMultiPost {
    suspend fun process(context: Context, files: List<File>, parts: List<Map<String, String>>): String
}

/** One file of a download job that has several: the first is the job's own, the others travel in its `more` argument. */
class DownloadPart(
    val url: String,
    /** The bare name in droidtop's downloads area ([DownloadJobs.fileFor]). */
    val name: String,
    /** The name the file is placed under. */
    val targetName: String,
    val sha256: String? = null,
    val sha1: String? = null,
    val md5: String? = null,
    val maxBytes: Long = 0L,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * The one runner for a HTTP(S) download of one file, or of several as one entry (docs/SPEC.md 12a "Downloads", Droidtop/tracker#181):
 * the download engine (libaria2 in its own process, docs/SPEC.md 12a "One download engine") does the transfer and
 * carries on from aria2's control file, so the job is a native job in the one [PluginJobsCenter] list that can Pause
 * and Resume, and survives a lost connection, a network change or a restart. Callers register a post-processing step under a name ([registerPost]) and run a job with [run].
 */
object DownloadJobs {
    const val KIND = "download"
    private const val OWNER = "Downloads"

    /** The post step that leaves the verified file where it landed; the caller takes it from [fileFor]. */
    const val POST_KEEP = "keep"
    const val POST_PLACE_IN_FOLDER = "place_in_folder"

    /**
     * The post step that moves the verified file to `targetPath`, a file in droidtop's own storage (a runtime's
     * component, a plugin repository's bundle), replacing what is there. The one download path for droidtop's own
     * files, so they move to the download engine with every other download (docs/SPEC.md 12a, "One download engine").
     */
    const val POST_MOVE_TO = "move_to"

    /**
     * The job argument holding the only sites a download may reach (a JSON array of `net.domains` entries): a plugin's
     * own, for a picture or a file a plugin names. Every address, the redirects included, must be https on one of them
     * ([RedirectGuard], docs/SPEC.md 12a). It is a job argument, so a restored job keeps it.
     */
    const val ARG_ALLOW_SITES = "allowSites"

    /** [POST_MOVE_TO]'s argument: the absolute path the file is moved to. */
    const val ARG_TARGET_PATH = "targetPath"

    const val DIGEST_MISMATCH = "the downloaded file does not match its published digest"

    private const val ARG_URL = "url"
    private const val ARG_NAME = "name"
    private const val ARG_TITLE = "title"
    private const val ARG_POST = "post"
    private const val ARG_SHA256 = "sha256"
    private const val ARG_SHA1 = "sha1"
    private const val ARG_MD5 = "md5"
    private const val ARG_UNPACK = "unpack"
    private const val ARG_MORE = "more"
    private const val ARG_TARGET = "targetName"

    /**
     * The job argument naming the new version folder an update lands in (docs/SPEC.md 7g, "Getting an update"):
     * `<name> v<version>` in `destinationPath`, beside the game's other versions. It must not exist yet; nothing that
     * is there is touched.
     */
    const val ARG_VERSION_FOLDER = "versionFolder"
    private val PART_KEYS = listOf(ARG_URL, ARG_NAME, ARG_TARGET, ARG_SHA256, ARG_SHA1, ARG_MD5, ARG_MAX_BYTES, ARG_HEADERS)

    /** The job argument value that unpacks the downloaded archive into a folder instead of placing the file. */
    const val UNPACK_ARCHIVE = "archive"
    private const val ARG_MAX_BYTES = "maxBytes"
    private const val ARG_HEADERS = "headers"

    private val posts = ConcurrentHashMap<String, DownloadPost>().apply { put(POST_KEEP, DownloadPost { _, _, _ -> "Downloaded" }) }

    /** The post steps that can finish a download of several files; the others take exactly one. */
    private val multiPosts = ConcurrentHashMap<String, DownloadMultiPost>()

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
    @Volatile var onPlaced: (context: Context, placed: File, args: Map<String, String>) -> String? = { _, _, _ -> null }

    /** Registers the runner with the one jobs registry. Called once at process start, before jobs are restored. */
    fun register(context: Context) {
        val appContext = context.applicationContext
        registerPost(POST_PLACE_IN_FOLDER) { jobContext, file, args ->
            withContext(Dispatchers.IO) {
                val target = if (args[ARG_UNPACK] == UNPACK_ARCHIVE) unpackIntoFolder(file, args) else placeInFolder(file, args)
                // What the job carries about the placed file beyond its bytes (a source's engine hint, an update's
                // saves), applied before the library looks at it, so the first index already reads it.
                val note = runCatching { onPlaced(jobContext, target, args) }.getOrNull()
                // The file is the library's from here, said by the job and not by whatever screen started it, so
                // a page that was closed, or a restart, loses nothing (docs/SPEC.md 7g, "Targeted indexing").
                LibraryPaths.report(jobContext, PathChange.added(target))
                listOfNotNull("Added ${target.name}", note).joinToString(". ")
            }
        }
        multiPosts[POST_PLACE_IN_FOLDER] = DownloadMultiPost { jobContext, files, parts ->
            val (placed, summary) = withContext(Dispatchers.IO) {
                (
                    when {
                        parts.first()[ARG_UNPACK] == UNPACK_ARCHIVE -> placeSplitSet(files, parts)
                        // An update's files are one new version folder, and that folder is what the library is told of.
                        parts.first()[ARG_VERSION_FOLDER] != null -> placeAllInFolder(files, parts).first().parentFile!!.let { listOf(it) to "Added ${it.name}" }
                        else -> placeAllInFolder(files, parts).let { it to "Added ${it.size} files" }
                    }
                    )
                    .let { (list, said) ->
                        val notes = list.mapNotNull { runCatching { onPlaced(jobContext, it, parts.first()) }.getOrNull() }
                        LibraryPaths.report(jobContext, PathChange(added = list.map { it.absolutePath }))
                        list to (listOf(said) + notes).joinToString(". ")
                    }
            }
            summary
        }
        registerPost(POST_MOVE_TO) { jobContext, file, args ->
            withContext(Dispatchers.IO) {
                val target = ownTarget(jobContext, requireNotNull(args[ARG_TARGET_PATH]) { "the target is missing" })
                target.parentFile?.mkdirs()
                // The new file replaces the old one only once it is whole in place: moved beside it first, then renamed.
                val staged = File(target.parentFile, ".${target.name}.new")
                staged.delete()
                moveFile(file, staged)
                check(staged.renameTo(target) || (target.delete() && staged.renameTo(target))) { "${target.name} could not be put in place" }
                "Downloaded ${target.name}"
            }
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

    /**
     * [path] when it is inside droidtop's own storage (its files, cache or external files folder), never anywhere else:
     * [POST_MOVE_TO] writes only droidtop's own files. Throws otherwise.
     */
    internal fun ownTarget(context: Context, path: String): File =
        ownTarget(listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null), context.noBackupFilesDir), path)

    /** [path] when it is inside one of [roots] once `..` and links are resolved; throws otherwise. */
    internal fun ownTarget(roots: List<File>, path: String): File {
        val target = File(path).canonicalFile
        require(roots.map { it.canonicalFile }.any { root -> target.path.startsWith(root.path + File.separator) }) { "a download is moved only into droidtop's own storage" }
        return target
    }

    /** Moves [file] to `targetName` in `destinationPath`; the one place a download is placed in a game folder. Returns where it went. */
    internal fun placeInFolder(file: File, args: Map<String, String>): File {
        args[ARG_VERSION_FOLDER]?.let { return placeAllInFolder(listOf(file), listOf(args)).single().parentFile!! }
        val destination = File(requireNotNull(args["destinationPath"]) { "the game folder is missing" })
        require(destination.isDirectory || destination.mkdirs()) { "the game folder ${destination.absolutePath} is not available" }
        // A name already in the folder keeps both (Droidtop/tracker#355 G5): the new one is "Name (2).ext", never a
        // failure after the whole download and never a replacement.
        val target = freeName(destination, requireNotNull(args["targetName"]) { "the file name is missing" })
        moveFile(file, target)
        return target
    }

    /**
     * [name] in [folder], or the first of "Name (2).ext", "Name (3).ext", ... that is free, so a download never replaces
     * what is there (Droidtop/tracker#355 G5). Throws when 99 copies already exist.
     */
    internal fun freeName(folder: File, name: String): File {
        val first = File(folder, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.').takeIf { it > 0 && !File(folder, name).isDirectory } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        return (2..99).asSequence().map { File(folder, "$stem ($it)$ext") }.firstOrNull { !it.exists() }
            ?: throw IllegalArgumentException("${first.absolutePath} and 98 copies already exist")
    }

    /**
     * Moves [from] to [to], also onto another volume: droidtop's downloads area is on the device's shared storage and a
     * game folder is often on an SD card, where a rename cannot go (EXDEV), so a download placed there failed with "could
     * not be placed in the game folder" (RP5, exFAT SD card, a BIOS file, Droidtop/tracker#558). When the rename is
     * refused, the bytes are copied under a hidden name in [to]'s folder, synced, renamed into place on that volume and
     * only then is [from] deleted, so a failure leaves the download where it was and no half file under [to]'s name.
     * Throws with [to]'s path when it cannot be done. [rename] is [File.renameTo] (a test refuses it).
     */
    fun moveFile(from: File, to: File, rename: (File, File) -> Boolean = { a, b -> a.renameTo(b) }) {
        if (rename(from, to)) return
        val folder = to.parentFile ?: throw IllegalStateException("${to.absolutePath} has no folder")
        val staged = File(folder, ".${to.name}.placing")
        try {
            from.inputStream().use { input ->
                java.io.FileOutputStream(staged).use { output ->
                    input.copyTo(output, 1 shl 16)
                    output.fd.sync()
                }
            }
            check(staged.length() == from.length()) { "only ${staged.length()} of ${from.length()} bytes were written" }
            check(rename(staged, to)) { "the copy could not be renamed into place" }
        } catch (e: Exception) {
            staged.delete()
            throw IllegalStateException("the download could not be placed at ${to.absolutePath}: ${e.message ?: e.javaClass.simpleName}", e)
        }
        from.delete()
    }

    /**
     * Moves every file of a download to its own target name in the destination, all or nothing: nothing is moved if
     * any target exists or two share a name, and a move that fails puts the ones already moved back. Returns the targets.
     */
    internal fun placeAllInFolder(files: List<File>, parts: List<Map<String, String>>): List<File> {
        val games = File(requireNotNull(parts.first()["destinationPath"]) { "the game folder is missing" })
        require(games.isDirectory || games.mkdirs()) { "the game folder ${games.absolutePath} is not available" }
        // An update's files go into a new version folder of their own, made here and never one that exists.
        val destination = parts.first()[ARG_VERSION_FOLDER]?.let { name ->
            val folder = newVersionFolder(games, name)
            require(folder.mkdir()) { "the folder ${folder.absolutePath} could not be made" }
            folder
        } ?: games
        val targets = parts.map { File(destination, requireNotNull(it[ARG_TARGET]) { "the file name is missing" }) }
        require(targets.map { it.name }.distinct().size == targets.size) { "two of the files have the same name" }
        targets.firstOrNull { it.exists() }?.let { throw IllegalArgumentException("${it.absolutePath} already exists") }
        val moved = mutableListOf<Pair<File, File>>()
        try {
            for ((file, target) in files.zip(targets)) {
                moveFile(file, target)
                moved += file to target
            }
        } catch (e: Exception) {
            moved.forEach { (file, target) -> runCatching { moveFile(target, file) } }
            throw e
        }
        return targets
    }

    /**
     * Finishes a download whose files were asked to be unpacked and are the parts of one split archive
     * ([SplitArchives]). Byte-split parts (`.7z.001`, `.zip.001`, `.rar.001`) are joined in order into one file, the parts
     * deleted, and the joined archive unpacked like any single one ([unpackIntoFolder]); a failed unpack keeps the joined
     * file. RAR volumes (`.part1.rar`) cannot be read by the extractor's libarchive binding, which opens one file, so
     * unpacking only the first would silently drop the rest: they are placed together as they are and the summary says so.
     * Returns what the library should look at, and the line the job shows.
     */
    internal suspend fun placeSplitSet(files: List<File>, parts: List<Map<String, String>>): Pair<List<File>, String> {
        val set = requireNotNull(SplitArchives.detect(parts.map { it[ARG_TARGET].orEmpty() })) {
            "unpacking takes one archive, or every part of one split archive"
        }
        if (set.volumes) {
            val placed = placeAllInFolder(files, parts)
            return placed to "Added ${placed.size} parts. droidtop cannot unpack split RAR files yet, so they are in the game folder as they are"
        }
        val joined = File(files.first().parentFile, "joined_${System.nanoTime()}.${set.extension}")
        try {
            joined.outputStream().buffered().use { out -> set.order.forEach { index -> files[index].inputStream().use { it.copyTo(out) } } }
        } catch (e: java.io.IOException) {
            joined.delete()
            throw IllegalStateException("could not join the parts: ${e.message}", e)
        }
        files.forEach { it.delete() }
        val folder = unpackIntoFolder(
            joined,
            buildMap {
                put("destinationPath", requireNotNull(parts.first()["destinationPath"]))
                put(ARG_TARGET, set.baseName)
                parts.first()[ARG_VERSION_FOLDER]?.let { put(ARG_VERSION_FOLDER, it) }
            },
        )
        return listOf(folder) to "Added ${folder.name}"
    }

    /**
     * Unpacks the downloaded archive (zip, 7z or rar) into a new folder of the target's name less its extension in the
     * destination, and deletes the archive. [ArchiveExtractor] proves every entry stays inside the folder and refuses
     * symlinks, encrypted entries and archive bombs. The folder is filled under a hidden name and renamed when whole,
     * so a crash leaves no half-unpacked game; an existing folder is never touched. A failed unpack keeps the download.
     */
    internal suspend fun unpackIntoFolder(file: File, args: Map<String, String>): File {
        val destination = File(requireNotNull(args["destinationPath"]) { "the game folder is missing" })
        require(destination.isDirectory || destination.mkdirs()) { "the game folder is not available" }
        val targetName = requireNotNull(args["targetName"]) { "the file name is missing" }
        val stem = requireNotNull(archiveStem(targetName)) { "$targetName is not a zip, 7z or rar archive" }
        val version = args[ARG_VERSION_FOLDER]
        // An existing folder is never unpacked over: the new game is "Name (2)" beside it (#355 G5), and an update's
        // version folder must be new.
        val folder = if (version != null) newVersionFolder(destination, version) else freeName(destination, stem)
        val building = File(destination, ".${folder.name}.unpacking")
        try {
            ArchiveExtractor.extract(file, building)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("could not unpack $targetName: ${e.message}. The download is kept in droidtop's downloads folder", e)
        }
        // A release usually packs one folder ("Game-0.6-pc/"): as a version folder, that folder's contents are the
        // version, so the game is not one level further down than its other versions.
        val inner = if (version != null) building.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } else null
        check((inner ?: building).renameTo(folder)) { "the unpacked game could not be placed in the game folder" }
        if (inner != null) building.delete()
        file.delete()
        return folder
    }

    /** [name] in [games] for an update's new version folder; throws when something of that name is already there. */
    internal fun newVersionFolder(games: File, name: String): File {
        require(name.isNotBlank() && '/' !in name && name != "." && name != "..") { "the version folder's name is not usable" }
        val folder = File(games, name)
        require(!folder.exists()) { "${folder.absolutePath} already exists; droidtop does not place a version over another" }
        return folder
    }

    /** The name of an archive less its extension, or null when [name] is not a zip, 7z or rar file. */
    fun archiveStem(name: String): String? =
        Regex("(?i)^(.+)\\.(zip|7z|rar)$").matchEntire(name)?.groupValues?.get(1)

    /** Names the post step that finishes a download of several files, as [registerPost] names one for a single file. */
    fun registerMultiPost(name: String, post: DownloadMultiPost) {
        multiPosts[name] = post
    }

    /** Names the post-processing step a job's `post` argument refers to. Registered at process start, so a restored job finds it. */
    fun registerPost(name: String, keepFile: Boolean = false, post: DownloadPost) {
        posts[name] = post
        if (keepFile) keptPosts += name else keptPosts -= name
    }

    /** Post steps that decide for themselves whether the downloaded file stays (the self-update keeps its APK until Android installed it). */
    private val keptPosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        /** [UNPACK_ARCHIVE] to unpack the downloaded archive into its own folder when placing it; null leaves it as the file it is. */
        unpack: String? = null,
        maxBytes: Long = 0L,
        headers: Map<String, String> = emptyMap(),
        /** Further files (1 to 15) fetched and placed together with the first, all or nothing, as one entry in Downloads. */
        more: List<DownloadPart> = emptyList(),
        extra: Map<String, String> = emptyMap(),
        /** The file's size when the caller knows it: the download policy lets a small one use mobile data. */
        sizeBytes: Long = 0L,
        /** True for a download nobody just asked for: the policy's update window and "while playing" apply to it too. */
        automatic: Boolean = false,
        onStatus: (String) -> Unit = {},
        /** The job's percent each time it changes (0 to 100; -1 while the size is not known). */
        onPercent: (Int) -> Unit = {},
        /** Which pool the download runs in (docs/SPEC.md 12a); null works it out from [post] and [extra] ([categoryFor]). */
        category: DownloadCategory? = null,
        /** The only sites the download may reach, redirects included ([ARG_ALLOW_SITES]); null for any. */
        allowSites: List<String>? = null,
    ): PluginResult {
        require(more.size < MAX_FILES) { "a download has at most $MAX_FILES files" }
        val plain = holdSecrets(name, headers)
        val morePlain = more.map { holdSecrets(it.name, it.headers) }
        val args = buildMap {
            putAll(extra)
            put(ARG_URL, url)
            put(ARG_NAME, name)
            put(ARG_TITLE, title)
            put(ARG_POST, post)
            sha256?.let { put(ARG_SHA256, it) }
            sha1?.let { put(ARG_SHA1, it) }
            md5?.let { put(ARG_MD5, it) }
            unpack?.let { put(ARG_UNPACK, it) }
            if (maxBytes > 0) put(ARG_MAX_BYTES, maxBytes.toString())
            if (plain.isNotEmpty()) put(ARG_HEADERS, JSONObject(plain).toString())
            if (more.isNotEmpty()) {
                put(ARG_MORE, JSONArray(more.mapIndexed { index, part ->
                    JSONObject().put(ARG_URL, part.url).put(ARG_NAME, part.name).put(ARG_TARGET, part.targetName).also { o ->
                        part.sha256?.let { o.put(ARG_SHA256, it) }
                        part.sha1?.let { o.put(ARG_SHA1, it) }
                        part.md5?.let { o.put(ARG_MD5, it) }
                        if (part.maxBytes > 0) o.put(ARG_MAX_BYTES, part.maxBytes.toString())
                        if (morePlain[index].isNotEmpty()) o.put(ARG_HEADERS, JSONObject(morePlain[index]).toString())
                    }
                }).toString())
            }
            put(DownloadPools.ARG_CATEGORY, (category ?: categoryFor(post, extra)).key)
            allowSites?.let { put(ARG_ALLOW_SITES, JSONArray(it).toString()) }
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
                        onPercent(entry.percent)
                    }
                }
            }
            try {
                finished.await()
            } finally {
                narrator.cancel()
                secretHeaders.remove(name)
                more.forEach { secretHeaders.remove(it.name) }
            }
        }
    }

    /**
     * The pool of a download whose caller named none: an update's new version folder is an update, a file placed in a
     * game folder is a game's, and anything droidtop keeps for itself is a system download.
     */
    internal fun categoryFor(post: String, extra: Map<String, String>): DownloadCategory = when {
        extra.containsKey(ARG_VERSION_FOLDER) -> DownloadCategory.UPDATES
        post == POST_PLACE_IN_FOLDER -> DownloadCategory.GAMES
        else -> DownloadCategory.SYSTEM
    }

    /** Splits [headers] of the download file [name]: the credential ones stay in memory, the rest are returned to be persisted. */
    private fun holdSecrets(name: String, headers: Map<String, String>): Map<String, String> {
        val (secret, plain) = headers.entries.partition { entry ->
            listOf("authorization", "proxy-authorization", "cookie", "credential", "token", "api-key", "secret")
                .any { marker -> entry.key.contains(marker, ignoreCase = true) }
        }
        if (secret.isNotEmpty()) secretHeaders[name] = secret.associate { it.key to it.value }
        return plain.associate { it.key to it.value }
    }

    /** The most files one download job has. */
    const val MAX_FILES = 16

    /** The job's files as one argument map each: the first is [args] itself; the others take their own over what the job shares. */
    internal fun partsOf(args: Map<String, String>): List<Map<String, String>> {
        val more = args[ARG_MORE] ?: return listOf(args)
        val array = JSONArray(more)
        val first = args - ARG_MORE
        val shared = first - PART_KEYS
        return listOf(first) + List(array.length()) { index ->
            val o = array.getJSONObject(index)
            shared + o.keys().asSequence().associateWith { o.getString(it) }
        }
    }

    private fun headersOf(part: Map<String, String>): Map<String, String> = buildMap {
        part[ARG_HEADERS]?.let { raw -> JSONObject(raw).let { o -> o.keys().forEach { put(it, o.getString(it)) } } }
        part[ARG_NAME]?.let { secretHeaders[it] }?.let { putAll(it) }
    }

    /** The checkpoint once every byte is in the file: a restart then goes on to the post step and never downloads again. */
    private const val FETCHED = "fetched"

    private fun cancelDownload(context: Context, args: Map<String, String>, checkpoint: String?) {
        partsOf(args).forEach { part ->
            part[ARG_NAME]?.let { name ->
                runCatching {
                    val file = fileFor(context, name)
                    file.delete()
                    TransferRequest.controlFile(file).delete()
                    // What the downloader before the engine left beside a file a job restored from then still holds.
                    File(file.path + ".part").delete()
                    File(file.path + ".part.meta").delete()
                }
            }
        }
    }

    internal suspend fun execute(
        context: Context,
        args: Map<String, String>,
        checkpoint: String?,
        report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
    ): String {
        val parts = partsOf(args)
        if (parts.size > 1) return executeMany(context, parts, checkpoint, report)
        val name = requireNotNull(args[ARG_NAME]) { "the download has no file name" }
        val postName = args[ARG_POST] ?: POST_KEEP
        val post = posts[postName] ?: throw IllegalStateException("this version of droidtop cannot finish that download")
        val headers = headersOf(args)
        val url = requireNotNull(args[ARG_URL]) { "the download has no address" }
        val file = fileFor(context, name)
        placedBeforeTheProcessEnded(file, postName, args, checkpoint) { LibraryPaths.report(context, PathChange.added(it)) }?.let { return it }
        if (!(checkpoint == FETCHED && file.isFile)) {
            transfer(context, args, url, headers, file, args[ARG_MAX_BYTES]?.toLongOrNull() ?: 0L, report)
            report(100, "Checking the download…", FETCHED)
        }
        verifyDigest(file, args)
        report(100, "Finishing…", null)
        val summary = post.process(context, file, args)
        if (postName != POST_KEEP && postName !in keptPosts) withContext(Dispatchers.IO) { file.delete() }
        return summary
    }

    /**
     * A job of several files: each is fetched and checked in turn into the downloads area, the checkpoint
     * `fetched:<n>` saying how many are whole (a restart carries on with the next, from its partial file), and only
     * then does the post step place them all together. A file that fails its digest fails the whole job; nothing is placed.
     */
    private suspend fun executeMany(
        context: Context,
        parts: List<Map<String, String>>,
        checkpoint: String?,
        report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
    ): String {
        val postName = parts.first()[ARG_POST] ?: POST_KEEP
        val multi = multiPosts[postName] ?: throw IllegalStateException("this version of droidtop cannot finish that download")
        val files = parts.map { fileFor(context, requireNotNull(it[ARG_NAME]) { "the download has no file name" }) }
        val done = checkpoint?.takeIf { it.startsWith("$FETCHED:") }?.substringAfter(':')?.toIntOrNull() ?: 0
        placedManyBeforeTheProcessEnded(files, parts, postName, done) { LibraryPaths.report(context, PathChange(added = it.map(File::getAbsolutePath))) }?.let { return it }
        for ((index, part) in parts.withIndex()) {
            if (index < done && files[index].isFile) continue
            val url = requireNotNull(part[ARG_URL]) { "the download has no address" }
            val prefix = "File ${index + 1} of ${parts.size}. "
            transfer(context, part, url, headersOf(part), files[index], part[ARG_MAX_BYTES]?.toLongOrNull() ?: 0L) { percent, line, _ ->
                report(if (percent < 0) -1 else (index * 100 + percent) / parts.size, prefix + line, null)
            }
            verifyDigest(files[index], part)
            report((index + 1) * 100 / parts.size, "Checking the download…", "$FETCHED:${index + 1}")
        }
        report(100, "Finishing…", null)
        val summary = multi.process(context, files, parts)
        withContext(Dispatchers.IO) { files.forEach { it.delete() } }
        return summary
    }

    /** The several-file counterpart of [placedBeforeTheProcessEnded]: every file is whole, none is in the downloads area, every target is in the game folder. */
    internal suspend fun placedManyBeforeTheProcessEnded(
        files: List<File>,
        parts: List<Map<String, String>>,
        postName: String,
        done: Int,
        reportPlaced: (List<File>) -> Unit,
    ): String? {
        if (postName != POST_PLACE_IN_FOLDER || done < parts.size) return null
        return withContext(Dispatchers.IO) {
            val games = File(parts.first()["destinationPath"] ?: return@withContext null)
            val destination = parts.first()[ARG_VERSION_FOLDER]?.let { File(games, it) } ?: games
            val targets = parts.map { File(destination, it[ARG_TARGET] ?: return@withContext null) }
            if (files.any { it.exists() } || !targets.all { it.isFile }) return@withContext null
            reportPlaced(targets)
            "Added ${targets.size} files"
        }
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
            val targetName = args["targetName"] ?: return@withContext null
            val unpacked = args[ARG_UNPACK] == UNPACK_ARCHIVE
            val games = args["destinationPath"] ?: return@withContext null
            val version = args[ARG_VERSION_FOLDER]
            val target = when {
                version != null -> File(games, version)
                else -> File(games, if (unpacked) (archiveStem(targetName) ?: return@withContext null) else targetName)
            }
            if (file.exists() || !(if (unpacked || version != null) target.isDirectory else target.isFile)) return@withContext null
            reportPlaced(target)
            "Added ${target.name}"
        }
    }

    /** One file of a job: every download is the download engine's (docs/SPEC.md 12a "One download engine"). */
    private suspend fun transfer(
        context: Context,
        args: Map<String, String>,
        url: String,
        headers: Map<String, String>,
        destination: File,
        maxBytes: Long,
        report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
    ) = fetchWithEngine(context, url, headers, destination, maxBytes, report, sitesGuard(args[ARG_ALLOW_SITES]))

    /**
     * The check every address of a download limited to [sites] (a JSON array, [ARG_ALLOW_SITES]) is put to: https, on one
     * of them. Null when the download is not limited. A list droidtop cannot read allows nothing.
     */
    internal fun sitesGuard(sites: String?): ((String) -> Unit)? {
        sites ?: return null
        val domains = runCatching { JSONArray(sites).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        return { address ->
            val host = NetScope.hostOf(address)
            if (!NetScope.isHttps(address) || host == null || domains.none { NetScope.matches(host, it) }) {
                // Not an IOException: the engine's fetch would take that for a lost connection and ask again.
                throw TransferException("${host ?: address} is not one of the sites this download may reach")
            }
        }
    }

    /**
     * Gets [url] into [destination] with the download engine (docs/SPEC.md 12a "One download engine"): libaria2 in its own
     * process, its files and sockets opened by droidtop's broker. [destination]'s folder is the engine's download folder.
     * Pause and Cancel cancel the job's coroutine, which removes the download from the engine and keeps the partial file
     * and aria2's control file beside it; Resume (or a restart) adds it again and aria2 carries on from them.
     */
    internal suspend fun fetchWithEngine(
        context: Context,
        url: String,
        headers: Map<String, String>,
        destination: File,
        maxBytes: Long,
        report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
        /** Put to every address before the engine is given one ([RedirectGuard.resolve]); throws to refuse it. */
        allow: ((String) -> Unit)? = null,
    ) {
        val folder = requireNotNull(destination.parentFile) { "the download has no folder" }
        val engine = try {
            val isolated = withContext(Dispatchers.IO) { EnginePrefs.androidIsolation(context) }
            EngineHost.engine(context, folder, isolated)
        } catch (e: TimeoutCancellationException) {
            coroutineContext.ensureActive()
            throw IllegalStateException("the download engine did not start in time")
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw IllegalStateException("the download engine could not start: ${e.message?.lineSequence()?.first().orEmpty()}")
        } catch (e: IllegalStateException) {
            // DownloadEngine.start says why in its message, with the end of libaria2's log after the first line.
            coroutineContext.ensureActive()
            throw IllegalStateException("the download engine could not start: ${e.message?.lineSequence()?.first().orEmpty()}")
        }
        try {
            EngineFetch.fetch(
                engine, TransferRequest(url, headers, destination, maxBytes),
                report = { percent, line -> report(percent, line, null) }, size = ValueFormat::size,
                resolve = { u, h -> RedirectGuard.resolve(u, h, allow = allow) },
            )
        } catch (e: TransferException) {
            throw IllegalStateException(e.message)
        }
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

/**
 * Recognises the files of one split archive by name (Droidtop/tracker#419): byte-split parts `X.7z.001`, `X.zip.001`
 * or `X.rar.001` with `.002`, ... (joined by concatenation), and RAR volumes `X.part1.rar`, `X.part2.rar`, ... All
 * names must belong to one set and be numbered 1 to N without a gap or a repeat, in any order.
 */
object SplitArchives {
    /** [order] lists the indexes of the names in part order; [baseName] is the archive's name (joined: `X.7z`; volumes: `X`). */
    class SplitSet(val baseName: String, val extension: String?, val order: List<Int>, val volumes: Boolean)

    private val NUMBERED = Regex("(?i)^(.+\\.(zip|7z|rar))\\.(\\d{3,})$")
    private val VOLUME = Regex("(?i)^(.+)\\.part(\\d+)\\.rar$")

    fun detect(names: List<String>): SplitSet? {
        if (names.size < 2) return null
        return numbered(names) ?: volumes(names)
    }

    private fun numbered(names: List<String>): SplitSet? {
        val matches = names.map { NUMBERED.matchEntire(it) ?: return null }
        if (matches.map { it.groupValues[1].lowercase() }.distinct().size != 1) return null
        val order = order(matches.map { it.groupValues[3].toIntOrNull() ?: return null }) ?: return null
        return SplitSet(matches.first().groupValues[1], matches.first().groupValues[2].lowercase(), order, volumes = false)
    }

    private fun volumes(names: List<String>): SplitSet? {
        val matches = names.map { VOLUME.matchEntire(it) ?: return null }
        if (matches.map { it.groupValues[1].lowercase() }.distinct().size != 1) return null
        val order = order(matches.map { it.groupValues[2].toIntOrNull() ?: return null }) ?: return null
        return SplitSet(matches.first().groupValues[1], "rar", order, volumes = true)
    }

    private fun order(numbers: List<Int>): List<Int>? {
        val order = numbers.indices.sortedBy { numbers[it] }
        return order.takeIf { list -> list.indices.all { numbers[list[it]] == it + 1 } }
    }
}

/** The download part of an acquire reply: one `download` descriptor, or `downloads`, a list of 1 to [DownloadJobs.MAX_FILES]. */
object AcquireDownloads {
    /** Null when the reply carries no download; an empty list when it carries an invalid one (or both forms). */
    fun parse(values: Map<String, String>): List<AcquireDownloadDescriptor>? {
        val single = values["download"]
        val many = values["downloads"]
        if (single == null && many == null) return null
        if (single != null && many != null) return emptyList()
        if (single != null) return listOfNotNull(AcquireDownloadDescriptor.parse(single))
        return runCatching {
            val array = JSONArray(many)
            require(array.length() in 1..DownloadJobs.MAX_FILES)
            List(array.length()) { requireNotNull(AcquireDownloadDescriptor.parse(array.getJSONObject(it).toString())) }
        }.getOrDefault(emptyList())
    }
}

/**
 * The name a source gives a download (docs/plugin-api.md 1.6, `fileName`): kept as given, spaces and brackets and
 * accents included, because the library reads regions and titles from file names. Only what no file system takes
 * is replaced (`: * ? " < > |` and control characters become `_`); a name with a path in it, a leading dot,
 * nothing left, or more than [MAX_BYTES] bytes of UTF-8 is refused.
 */
object AcquireFileName {
    const val MAX_BYTES = 200
    private val ILLEGAL = Regex("[:*?\"<>|\\p{Cc}]")

    fun clean(raw: String): String? {
        if (raw.any { it == '/' || it == '\\' }) return null
        val name = raw.replace(ILLEGAL, "_").trim().trimEnd('.', ' ')
        if (name.isEmpty() || name.startsWith(".")) return null
        return name.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    }

    /** A bare, safe name for the file in droidtop's downloads area: the extension of [display], nothing else of it. */
    fun areaName(stamp: Long, display: String): String {
        val extension = display.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        return "acquire_$stamp" + (extension?.let { ".$it" } ?: "")
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
    /** True when the reply asked for `unpack: "archive"`: the zip, 7z or rar is unpacked into its own folder when placed. */
    val unpack: Boolean = false,
    /** A download a page started in the plugin's web session, named by the one-use token `web.session open_in_session` gave (docs/plugin-api.md 3 G3). */
    val session: String? = null,
    /**
     * `"github"`: droidtop adds the person's GitHub token to the request where GitHubAuth allows it (a private
     * repository's release asset), held by Downloads in memory only, never shown to the plugin. Honoured only for a
     * plugin granted `github.api` (docs/plugin-api.md 3 G4).
     */
    val auth: String? = null,
) {
    companion object {
        const val AUTH_GITHUB = "github"

        fun parse(json: String?): AcquireDownloadDescriptor? = runCatching {
            val value = JSONObject(requireNotNull(json))
            val url = value.getString("url")
            val parsedUrl = URI(url)
            require(parsedUrl.scheme in setOf("http", "https") && !parsedUrl.host.isNullOrBlank())
            val fileName = requireNotNull(AcquireFileName.clean(value.getString("fileName")))
            val unpackWord = value.optString("unpack", "none")
            require(unpackWord == "none" || unpackWord == DownloadJobs.UNPACK_ARCHIVE)
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
            val auth = value.optString("auth").takeIf { it.isNotEmpty() }
            require(auth == null || auth == AUTH_GITHUB)
            AcquireDownloadDescriptor(url, headers, fileName, digest, size, sha1, md5, unpackWord == DownloadJobs.UNPACK_ARCHIVE, session, auth)
        }.getOrNull()
    }
}
