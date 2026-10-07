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

    /**
     * [relativePath] under [baseDir] matched case-insensitively segment by
     * segment, the way Windows reads a path a store's file list names, or
     * null when it is not there (GameNative's FileUtils.findFileCaseInsensitive).
     */
    fun findCaseInsensitive(baseDir: File, relativePath: String): File? {
        val direct = File(baseDir, relativePath.replace('\\', '/'))
        if (direct.exists()) return direct
        return resolveCaseInsensitive(baseDir, relativePath).takeIf { it.exists() }
    }

    /**
     * [relativePath] under [baseDir], each segment matched against the casing
     * on disk where one exists and kept as written where none does yet, so a
     * new file lands beside its differently cased siblings.
     */
    fun resolveCaseInsensitive(baseDir: File, relativePath: String): File {
        var current = baseDir
        for (segment in relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: File(current, segment)
        }
        return current
    }

    private val ID_UNSAFE = Regex("[^a-zA-Z0-9_-]")

    /**
     * An identifier (an app name, a namespace, a catalog id) as one file name
     * part: anything but ASCII letters, digits, '_' and '-' becomes '_'
     * (GameNative's sanitizeForFilename, so files it named are found again).
     */
    fun idPart(id: String): String = ID_UNSAFE.replace(id, "_")

    /** One number over [files]' modification times: a store's change stamp. One stat per file. */
    fun stamp(files: List<File>): Long {
        var stamp = 17L
        for (file in files) stamp = 31 * (31 * stamp + file.path.hashCode()) + file.lastModified()
        return stamp
    }
}
