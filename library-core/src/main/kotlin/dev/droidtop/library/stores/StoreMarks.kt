package dev.droidtop.library.stores

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.droidtop.net.Http
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch

/**
 * Each store's own mark (owner, 2026-10-14, Droidtop/tracker#503: "yep. we should always be able to show it even when
 * we can't bundle it, by caching it on login"). droidtop bundles no store's logo. Once a store is signed in, its mark
 * is read from the store's own site ([StoreLibrary.siteUrl]): the icon its home page declares, else its favicon. It
 * is kept as a small PNG in droidtop's files and drawn from there; the generic glyph is the fallback until then.
 * Network and file work: never on the main thread.
 */
object StoreMarks {
    /** The size a mark is kept at: more than any row's glyph shows, small enough to decode per row. */
    const val SIZE_PX = 96

    /** An icon larger than this is not a mark. */
    private const val MAX_BYTES = 512L * 1024

    /** Stores already asked in this process, so a failed fetch is not repeated on every redraw. */
    private val asked = ConcurrentHashMap.newKeySet<String>()

    private fun dir(context: Context) = File(context.filesDir, "store-marks")

    /** The cached mark of store [storeId], or null before one was fetched. A file check. */
    fun cached(context: Context, storeId: String): File? = File(dir(context), "$storeId.png").takeIf { it.isFile && it.length() > 0 }

    /**
     * Fetches [store]'s mark if it is signed in and has none yet, once per process. True when a new mark was saved
     * (the caller redraws). Blocks on the network.
     */
    fun ensure(context: Context, store: StoreLibrary): Boolean {
        val site = store.siteUrl ?: return false
        if (cached(context, store.id) != null || !store.signedIn(context) || !asked.add(store.id)) return false
        val page = runCatching { Http.get(site, maxBytes = 2L * 1024 * 1024).text() }.getOrNull()
        val candidates = iconCandidates(page.orEmpty(), site)
        for (url in candidates) {
            val bitmap = runCatching { decode(Http.get(url, maxBytes = MAX_BYTES).body) }.getOrNull() ?: continue
            save(context, store.id, bitmap)
            return true
        }
        return false
    }

    /** [ensure], off the caller's thread: after a sign-in, and when a signed-in store is listed without a mark. */
    fun ensureLater(context: Context, store: StoreLibrary) {
        val app = context.applicationContext
        if (store.siteUrl == null || cached(app, store.id) != null || store.id in asked) return
        scope.launch { runCatching { ensure(app, store) } }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun decode(bytes: ByteArray): Bitmap? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (decoded.width < 16 || decoded.height < 16) return null
        return if (decoded.width == SIZE_PX && decoded.height == SIZE_PX) decoded else Bitmap.createScaledBitmap(decoded, SIZE_PX, SIZE_PX, true)
    }

    private fun save(context: Context, storeId: String, bitmap: Bitmap) {
        val folder = dir(context).apply { mkdirs() }
        val part = File(folder, "$storeId.png.part")
        part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        part.renameTo(File(folder, "$storeId.png"))
    }

    private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val REL = Regex("\\brel\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val HREF = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val SIZES = Regex("\\bsizes\\s*=\\s*[\"'](\\d+)x\\d+[\"']", RegexOption.IGNORE_CASE)

    /**
     * Where a site's mark may be, best first, from its home page [html] at [pageUrl]: the touch icons it declares
     * (a square picture made for a home screen), then its other icons by declared size, largest first, then
     * `/favicon.ico`. Links are made absolute against the page; only http(s) links are kept. Pure.
     */
    fun iconCandidates(html: String, pageUrl: String): List<String> {
        data class Icon(val url: String, val touch: Boolean, val size: Int)
        val base = runCatching { URI(pageUrl) }.getOrNull()
        val icons = LINK.findAll(html).mapNotNull { match ->
            val tag = match.value
            val rel = REL.find(tag)?.groupValues?.get(1)?.lowercase()?.split(Regex("\\s+")) ?: return@mapNotNull null
            val touch = rel.any { it.startsWith("apple-touch-icon") }
            if (!touch && "icon" !in rel) return@mapNotNull null
            val href = HREF.find(tag)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&") ?: return@mapNotNull null
            val url = runCatching { base?.resolve(href)?.toString() ?: href }.getOrNull() ?: return@mapNotNull null
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            Icon(url, touch, SIZES.find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        }.toList()
        val ordered = icons.sortedWith(compareByDescending<Icon> { it.touch }.thenByDescending { it.size }).map { it.url }
        val favicon = base?.resolve("/favicon.ico")?.toString()
        return (ordered + listOfNotNull(favicon)).distinct()
    }
}
