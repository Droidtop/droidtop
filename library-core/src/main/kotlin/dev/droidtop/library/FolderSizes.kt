package dev.droidtop.library

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * How much room a game folder takes, measured on demand and kept by the
 * folder's stamp (its path and modification time): the one measurement the
 * game page's Size and the Storage page's PC rows read (docs/SPEC.md 7j
 * "Places", Droidtop/tracker#397 slice E). A folder whose time has not moved
 * is answered from memory with one `stat`; a folder it cannot read part of
 * is skipped there and its size is marked [Size.atLeast]. Measuring walks
 * the folder: off the main thread, and never while a list is drawn.
 */
object FolderSizes {
    /** A folder's size, and whether part of it could not be read (so it is at least that). */
    data class Size(val bytes: Long, val atLeast: Boolean = false)

    private data class Stamp(val path: String, val mtime: Long)

    private val kept = ConcurrentHashMap<Stamp, Size>()

    /** Past this many folders the memory starts over rather than growing with the library. */
    private const val MAX_KEPT = 4096

    /** The kept size of [path] for its stamp now, or null when it was never measured or has changed: one `stat`. */
    fun cached(path: String): Size? {
        val mtime = File(path).lastModified()
        return if (mtime == 0L) null else kept[Stamp(path, mtime)]
    }

    /**
     * Measures [path] and keeps the answer by its stamp; a kept answer for an
     * unchanged folder is returned without a walk. Null when the folder is
     * not there, or when [cancelled] said stop part way (nothing is kept then).
     */
    fun measure(path: String, cancelled: () -> Boolean = { false }): Size? {
        val folder = File(path)
        if (!folder.isDirectory) return null
        val stamp = Stamp(path, folder.lastModified())
        kept[stamp]?.let { return it }
        var bytes = 0L
        var partial = false
        val pending = ArrayDeque<File>().apply { add(folder) }
        while (pending.isNotEmpty()) {
            if (cancelled()) return null
            val children = pending.removeFirst().listFiles()
            if (children == null) {
                partial = true
                continue
            }
            for (child in children) {
                if (child.isDirectory) pending.add(child) else bytes += child.length()
            }
        }
        val size = Size(bytes, partial)
        if (stamp.mtime != 0L) {
            if (kept.size >= MAX_KEPT) kept.clear()
            kept[stamp] = size
        }
        return size
    }

    /** "2.1 GB", or "at least 2.1 GB" when part of the folder could not be read. */
    fun words(size: Size, format: (Long) -> String): String =
        if (size.atLeast) "at least ${format(size.bytes)}" else format(size.bytes)
}
