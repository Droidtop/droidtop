package dev.droidtop.runtime.windows

import java.io.File

/**
 * A prefix's drive letters, read and extended from the string a container keeps them in
 * (`D:/storage/emulated/0/DownloadE:/data/data/dev.droidtop.app/storage`: a letter, a colon and a
 * folder, one after another with no separator, the same string `Container.drivesIterator` walks).
 * Pure, so the rule that decides which letter a folder is reached at is tested without a device.
 */
object PrefixDrives {

    /** The letter and folder of every drive in [drives]. */
    fun entries(drives: String): List<Pair<Char, String>> {
        val result = ArrayList<Pair<Char, String>>()
        var colon = drives.indexOf(':')
        while (colon > 0) {
            val next = drives.indexOf(':', colon + 1)
            val end = if (next != -1) next - 1 else drives.length
            result += drives[colon - 1] to drives.substring(colon + 1, end)
            colon = next
        }
        return result
    }

    /** [drives] after [withFolder]: the string to keep, the letter [folder] is reached at, and whether a drive was added. */
    data class Mapped(val drives: String, val letter: Char, val added: Boolean)

    /**
     * Makes [folder] reachable from the prefix: the letter of a drive that already holds it, else a new
     * drive on the first free letter from D to Y (C: is the prefix, Z: is Wine's own system folder).
     * Null for a folder that cannot be mapped: a path with a colon in it would corrupt every drive after
     * it in the string (the rule [dev.droidtop.library.WineDriveMapping] keeps), and there are only so
     * many letters.
     */
    fun withFolder(drives: String, folder: File): Mapped? {
        val path = folder.absolutePath.trimEnd('/')
        if (path.isEmpty() || path.contains(':')) return null
        entries(drives).firstOrNull { (_, root) ->
            val base = root.trimEnd('/')
            base.isNotEmpty() && (path == base || path.startsWith("$base/"))
        }?.let { return Mapped(drives, it.first, added = false) }
        val letter = ('D'..'Y').firstOrNull { !drives.contains("$it:") } ?: return null
        return Mapped(drives + "$letter:$path", letter, added = true)
    }
}
