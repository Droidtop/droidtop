package dev.droidtop.library.integrations

import android.content.Context
import android.net.Uri
import android.os.Environment
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.net.DownloadCategory
import dev.droidtop.net.engine.SwarmSource
import dev.droidtop.pluginhost.AcquireFileName
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.LinkPattern
import java.io.File
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads the person adds themselves (docs/SPEC.md 12a, "Adding a download"): an https link, a magnet, or a `.torrent`
 * or Metalink file, typed or picked in Downloads > Add download, or sent to droidtop by another app (a magnet link, a
 * `.torrent` or `.metalink` file opened with droidtop). Nothing downloads until the person picks where it goes, each time:
 * the PC games folder, where it becomes a game (a torrent as one folder), or droidtop's folder in Download, where it is
 * only saved. The owner: "Yes, as a choice", "Ask each time", "Everything".
 */
object ManualDownloads {
    /** The Add download screen's registry id, registered by :app. */
    const val SCREEN_ID = "downloads_add"

    /** The router's kind for droidtop's own Downloads as a magnet handler ([LinkRouter]). */
    const val LINK_KIND = "downloads"

    /** What was offered and waits for a place: one at a time, the newest replacing the last. */
    sealed class Offer(val label: String) {
        class Link(val url: String, label: String) : Offer(label)
        class Magnet(val uri: String, label: String) : Offer(label)

        /** A `.torrent` ([DownloadJobs.SWARM_TORRENT]) or a Metalink ([DownloadJobs.SWARM_METALINK]) given as a file. */
        class File(val swarm: String, val bytes: ByteArray, label: String) : Offer(label)
    }

    @Volatile var pending: Offer? = null
        private set

    /** Offers what [text] is, or says why it is not something droidtop downloads. Pure apart from keeping the offer. */
    fun offerText(text: String): String? = when (val got = read(text.trim())) {
        is Offer -> {
            pending = got
            null
        }
        else -> got as String
    }

    /** What a typed or received link is: an [Offer], or the sentence why not. Pure. */
    internal fun read(text: String): Any = when {
        text.isEmpty() -> "Type or paste a link first"
        text.startsWith("magnet:", ignoreCase = true) ->
            if (SwarmSource.isMagnet(text) && text.length <= MAGNET_MAX) Offer.Magnet(text, magnetName(text)) else "That is not a torrent's magnet link"
        text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true) -> {
            val uri = runCatching { URI(text) }.getOrNull()
            if (uri?.host.isNullOrBlank()) "That link has no site in it" else Offer.Link(text, linkName(text))
        }
        else -> "Give an https link, a magnet link, or pick a .torrent or .metalink file"
    }

    /** Offers a picked or received file's [bytes], named [name]; null when offered, else the sentence why not. */
    fun offerFile(name: String, bytes: ByteArray): String? {
        val stem = name.substringBeforeLast('.').ifBlank { name }
        val offer = when {
            DownloadJobs.looksLikeTorrent(bytes) -> Offer.File(DownloadJobs.SWARM_TORRENT, bytes, stem)
            DownloadJobs.looksLikeMetalink(bytes) -> Offer.File(DownloadJobs.SWARM_METALINK, bytes, stem)
            else -> return "That file is not a .torrent or a Metalink"
        }
        pending = offer
        return null
    }

    /** Reads a picked or received file, at most [DownloadJobs.TORRENT_MAX_BYTES]; null when it cannot be read or is larger. Disk. */
    fun readSmallFile(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > DownloadJobs.TORRENT_MAX_BYTES) return@runCatching null
            }
            out.toByteArray()
        }
    }.getOrNull()

    fun forget() {
        pending = null
    }

    /** droidtop's folder in the shared Download folder, where a download the person only wants saved goes. */
    fun savedFolder(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "droidtop")

    /** Where the jobs are started from: a job lives in Downloads, not on the screen that started it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Starts [offer] as a Downloads job into [folder]: the library's ([toGames], placed as a game) or one only saved in.
     * Returns at once with the line to show; the job goes on in Downloads, where it ends.
     */
    suspend fun start(context: Context, offer: Offer, folder: File, toGames: Boolean): String {
        val ready = withContext(Dispatchers.IO) { (folder.isDirectory || folder.mkdirs()) && folder.canWrite() }
        if (!ready) return "droidtop cannot write to ${folder.absolutePath}. It needs access to that storage (Settings > Storage)"
        val now = System.currentTimeMillis()
        val target = AcquireFileName.clean(offer.label) ?: "Download"
        val (url, name, swarm, bytes) = when (offer) {
            is Offer.Link -> Quad(offer.url, AcquireFileName.areaName(now, target), null, null)
            is Offer.Magnet -> Quad(offer.uri, AcquireFileName.areaName(now, ""), DownloadJobs.SWARM_MAGNET, null)
            is Offer.File -> Quad("", AcquireFileName.areaName(now, ""), offer.swarm, offer.bytes)
        }
        if (pending === offer) pending = null
        val app = context.applicationContext
        scope.launch {
            DownloadJobs.run(
                context = app,
                title = target,
                post = if (toGames) DownloadJobs.POST_PLACE_IN_FOLDER else DownloadJobs.POST_SAVE,
                url = url,
                name = name,
                extra = mapOf("destinationPath" to folder.absolutePath, "targetName" to target),
                category = if (toGames) DownloadCategory.GAMES else DownloadCategory.SYSTEM,
                swarm = swarm,
                torrent = bytes,
            )
        }
        return "Downloading $target: it is in Downloads"
    }

    private data class Quad(val url: String, val name: String, val swarm: String?, val bytes: ByteArray?)

    /** The longest magnet link taken, as a source's ([dev.droidtop.pluginhost.AcquireDownloadDescriptor.MAGNET_MAX_CHARS]). */
    private const val MAGNET_MAX = 8192

    /** A magnet's name: its `dn`, else "Magnet download". Pure. */
    internal fun magnetName(uri: String): String {
        val query = uri.substringAfter('?', "")
        val dn = query.split('&').firstOrNull { it.startsWith("dn=", ignoreCase = true) }?.substringAfter('=')
        return dn?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() } ?: "Magnet download"
    }

    /** A link's name: its last path segment, decoded, else its site. Pure. */
    internal fun linkName(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val last = uri?.rawPath?.substringAfterLast('/')?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        return last?.takeIf { it.isNotBlank() } ?: uri?.host ?: "Download"
    }

    /** droidtop's Downloads as the router's handler for magnet links, beside any plugin that takes them. */
    val linkSource = LinkRouter.Source { _ ->
        listOf(LinkRouter.Handler(LINK_KIND, "$LINK_KIND:magnet", "Downloads", LinkPattern("magnet"), ""))
    }

    /** Opening a magnet link in droidtop's Downloads: it is offered, and Add download asks where it goes. */
    val linkKind: LinkKind = { _, _, link ->
        when (val why = offerText(link)) {
            null -> LinkRouter.Result.Open(SCREEN_ID)
            else -> LinkRouter.Result.Message(why)
        }
    }

    /** Downloads > Add download: what to download, then where. */
    fun screen(): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Add download",
        subtitle = "An https or magnet link, or a .torrent or .metalink file. droidtop asks where to put it",
        groups = { context ->
            val offer = pending
            val pc = withContext(Dispatchers.IO) { runCatching { SourceScreens.entryContext(context).pcFolder }.getOrNull() }
            listOfNotNull(
                offer?.let { placeGroup(it, pc) },
                CatalogGroup(
                    id = "downloads_add_input",
                    title = if (offer == null) null else "Something else",
                    items = listOf(
                        TextInputItem(
                            id = "downloads_add_link",
                            title = "A link or a magnet",
                            subtitle = "https://… or magnet:?xt=urn:btih:…",
                            value = "",
                            onChange = { ctx, text ->
                                offerText(text)?.let { dev.droidtop.library.settings.Notices.flash(it, context = ctx) }
                            },
                        ),
                        DocumentPickItem(
                            id = "downloads_add_file",
                            title = "A .torrent or .metalink file",
                            subtitle = "From this device",
                            mimeType = "*/*",
                            onPicked = { ctx, uri ->
                                val bytes = withContext(Dispatchers.IO) { readSmallFile(ctx, uri) }
                                    ?: return@DocumentPickItem "That file could not be read, or is larger than a torrent can be"
                                offerFile(uri.lastPathSegment?.substringAfterLast('/') ?: "Download", bytes) ?: "Choose where it goes"
                            },
                        ),
                    ),
                ),
            )
        },
    )

    private fun placeGroup(offer: Offer, pc: File?): CatalogGroup {
        val kind = when (offer) {
            is Offer.Link -> "File"
            is Offer.Magnet -> "Magnet"
            is Offer.File -> if (offer.swarm == DownloadJobs.SWARM_METALINK) "Metalink" else "Torrent"
        }
        val items = buildList<CatalogItem> {
            if (pc != null) {
                add(
                    AsyncActionItem(
                        id = "downloads_add_to_games",
                        title = "Download into the PC games folder",
                        subtitle = "It becomes a game in your library${if (offer is Offer.Link) "" else ", as one folder"}. ${pc.absolutePath}",
                        run = { ctx, _ -> start(ctx, offer, pc, toGames = true) },
                    ),
                )
            }
            add(
                AsyncActionItem(
                    id = "downloads_add_to_saved",
                    title = "Download to droidtop's Downloads folder",
                    subtitle = "Only saved, not added to the library. ${savedFolder().absolutePath}",
                    run = { ctx, _ -> start(ctx, offer, savedFolder(), toGames = false) },
                ),
            )
            add(ActionItem(id = "downloads_add_forget", title = "Don't download it", run = { forget() }))
        }
        return CatalogGroup(id = "downloads_add_place", title = "$kind: ${offer.label}", items = items)
    }
}
