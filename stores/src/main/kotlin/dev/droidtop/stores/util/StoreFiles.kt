package dev.droidtop.stores.util

import java.io.File

/** Small file rules the stores share. */
internal object StoreFiles {
    private val UNSAFE = Regex("[^A-Za-z0-9 ._-]")

    /**
     * A game's folder name under its store's folder: the title with every
     * character a filesystem may refuse dropped (FAT and exFAT cards refuse
     * more than ext4, SPEC 7g), or [fallback] when nothing is left.
     */
    fun folderName(title: String, fallback: String): String =
        title.replace(UNSAFE, "").trim().trimEnd('.').ifBlank { fallback }

    /** One number over [files]' modification times: a store's change stamp. One stat per file. */
    fun stamp(files: List<File>): Long {
        var stamp = 17L
        for (file in files) stamp = 31 * (31 * stamp + file.path.hashCode()) + file.lastModified()
        return stamp
    }
}
