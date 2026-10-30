package dev.droidtop.library.guides

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.droidtop.library.GameResources
import dev.droidtop.library.LibraryEntry
import java.io.File

/**
 * What every guide reader reads (docs/SPEC.md 7i "Guides", Droidtop/tracker#817): the pad's in the game menu, the
 * companion's pane beside the running game. One list of a game's guides, one way to read a text guide, one way to draw
 * a PDF's pages, so the readers differ only in how they are driven. Everything here reads files: call it off the main
 * thread.
 */
object GuideContent {
    /** The most of a text guide read: about two million characters, more than any walkthrough. */
    const val MAX_TEXT_BYTES = 2_000_000

    /** The game's own folder, where its walkthroughs and readmes are looked for; null for a game without one. Pure. */
    fun folderOf(entry: LibraryEntry): String? =
        entry.pcInfo?.installPath?.takeIf { it.startsWith("/") } ?: entry.id.takeIf { it.startsWith("/") }

    /** The guides in the game's folder, then the web searches by its name (the resources rows). */
    fun forGame(folder: String?, gameName: String): List<Guide> =
        folder?.let { GameGuides.inFolder(File(it)) }.orEmpty() +
            GameResources.forGame(gameName).map { Guide("web:${it.label}", it.label, it.detail, GuideFormat.WEB, url = it.url) }

    /** A text guide's paragraphs, at most [MAX_TEXT_BYTES] of it; null when it cannot be read. */
    fun paragraphs(guide: Guide): List<String>? = runCatching {
        val file = File(checkNotNull(guide.path))
        // Read in a loop: InputStream.readNBytes is Android 13 and later.
        val bytes = ByteArray(minOf(file.length(), MAX_TEXT_BYTES.toLong()).toInt())
        var read = 0
        file.inputStream().use { input ->
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
        }
        String(bytes, 0, read, Charsets.UTF_8).split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrNull()
}

/** The first mark after [here], else null: Next mark goes forward through the marks. Pure. */
fun nextMark(marks: List<GuideBookmark>, here: GuidePosition): GuideBookmark? =
    marks.sortedWith(compareBy({ it.position.page }, { it.position.fraction }))
        .firstOrNull { it.position.page > here.page || (it.position.page == here.page && it.position.fraction > here.fraction + 0.001f) }

/** One open PDF guide, its pages rendered one at a time at a given width. [pageCount] is -1 when it cannot be opened. */
class GuidePdf(path: String?) {
    private val descriptor = runCatching { ParcelFileDescriptor.open(File(checkNotNull(path)), ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
    private val renderer = descriptor?.let { runCatching { PdfRenderer(it) }.getOrNull() }
    val pageCount: Int = renderer?.pageCount ?: -1

    @Synchronized
    fun render(index: Int, width: Int): Bitmap? {
        val pdf = renderer ?: return null
        if (index !in 0 until pdf.pageCount || width <= 0) return null
        return runCatching {
            pdf.openPage(index).use { page ->
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }.getOrNull()
    }

    @Synchronized
    fun close() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
    }
}
