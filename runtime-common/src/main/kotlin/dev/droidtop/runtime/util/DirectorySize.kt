package dev.droidtop.runtime.util

import java.io.File
import java.nio.file.Files

/**
 * The bytes of every file under this folder (this file's own length for a file; 0 for nothing there). Symbolic links
 * are not followed, so a link back into the tree (GOG's script interpreter `rootdir`, which points at the install
 * root) is not counted twice and cannot loop. A folder that cannot be read counts as empty. The one size-of-a-folder
 * helper (Droidtop/tracker#766): store installs, chunk caches and Windows prefixes all use it. Runs on the caller's
 * thread, so never on the main thread.
 */
fun File.sizeOnDisk(): Long {
    if (Files.isSymbolicLink(toPath())) return 0L
    if (isFile) return length()
    if (!isDirectory) return 0L
    return runCatching { listFiles() }.getOrNull().orEmpty().sumOf { it.sizeOnDisk() }
}
