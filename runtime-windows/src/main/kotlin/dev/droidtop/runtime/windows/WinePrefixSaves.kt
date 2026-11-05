package dev.droidtop.runtime.windows

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * A game's saves, carried from one prefix to another when the person moves the game (docs/SPEC.md 5a "Per-game
 * prefixes", piece 2: "Copy this game's saves to the new prefix: the save locations (`drive_c/users/ * /Documents`,
 * `AppData`, `Saved Games`) are copied, never overwriting a name that is already there"; Droidtop/tracker#584).
 *
 * Links are never followed or copied: a prefix's user folders can be links to the device's storage, and a copy that
 * followed one would copy that storage. Registry values are not carried. Disk work: never on the main thread.
 */
object WinePrefixSaves {
    internal val SAVE_DIRS = listOf("Documents", "AppData", "Saved Games")

    /** Copies the saves of every user in [fromPrefix] into [toPrefix] (each a container's root). Returns the files copied. */
    fun copy(fromPrefix: File, toPrefix: File): Int {
        val fromUsers = File(fromPrefix, ".wine/drive_c/users").toPath()
        val toUsers = File(toPrefix, ".wine/drive_c/users").toPath()
        if (!Files.isDirectory(fromUsers, LinkOption.NOFOLLOW_LINKS)) return 0
        var copied = 0
        val users = runCatching { Files.list(fromUsers).use { stream -> stream.toList() } }.getOrDefault(emptyList())
        for (user in users) {
            if (!Files.isDirectory(user, LinkOption.NOFOLLOW_LINKS)) continue
            for (name in SAVE_DIRS) {
                val source = user.resolve(name)
                if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) continue
                copied += copyTree(source, toUsers.resolve(user.fileName.toString()).resolve(name))
            }
        }
        return copied
    }

    /** Copies regular files under [source] to the same place under [target], skipping links and names already there. */
    private fun copyTree(source: Path, target: Path): Int {
        var copied = 0
        runCatching {
            Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val to = target.resolve(source.relativize(file).toString())
                    if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE
                    runCatching { Files.copy(file, to, LinkOption.NOFOLLOW_LINKS) }.onSuccess { copied++ }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        return copied
    }
}
