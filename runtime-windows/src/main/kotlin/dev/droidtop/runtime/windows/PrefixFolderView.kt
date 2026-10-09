package dev.droidtop.runtime.windows

import java.io.File
import java.nio.file.Files

/**
 * What is in one folder of a Wine prefix, for the read-only view of it (docs/SPEC.md 7c, "Prefix
 * tools"). A symbolic link is listed as a link and never followed: the prefix's drive letters are links
 * to the person's game folders and to the Windows system files, and a view of the prefix must not
 * wander into them or offer anything to do with them. Folders first, then files, each by name.
 * Disk work: the caller is off the main thread.
 */
object PrefixFolderView {

    /** One entry: a folder, a plain file ([size] in bytes), or a link ([target] is where it points). */
    data class Entry(val name: String, val directory: Boolean, val link: Boolean, val size: Long, val target: String?)

    /** [entries] is at most [limit] long; [more] is how many further entries the folder holds. */
    data class Listing(val entries: List<Entry>, val more: Int)

    fun list(folder: File, limit: Int = DEFAULT_LIMIT): Listing {
        val children = folder.listFiles().orEmpty()
        val all = children.map { child ->
            val path = child.toPath()
            if (Files.isSymbolicLink(path)) {
                Entry(child.name, directory = false, link = true, size = 0L, target = runCatching { Files.readSymbolicLink(path).toString() }.getOrNull())
            } else {
                Entry(child.name, directory = child.isDirectory, link = false, size = if (child.isFile) child.length() else 0L, target = null)
            }
        }.sortedWith(compareBy<Entry>({ !it.directory }, { it.name.lowercase() }, { it.name }))
        return Listing(all.take(limit), (all.size - limit).coerceAtLeast(0))
    }

    /** Whether [folder] is [root] or inside it, by real path: the view never opens anything outside the prefix. */
    fun isInside(root: File, folder: File): Boolean = runCatching {
        val base = root.canonicalFile.toPath()
        folder.canonicalFile.toPath().startsWith(base)
    }.getOrDefault(false)

    private const val DEFAULT_LIMIT = 400
}
