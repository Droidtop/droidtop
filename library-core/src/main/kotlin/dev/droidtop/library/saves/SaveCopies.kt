package dev.droidtop.library.saves

import android.content.Context
import dev.droidtop.library.GameEngine
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.toLibraryEntryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Copying saves from one copy of a game to another (docs/SPEC.md 7o, "Saves between copies", Droidtop/tracker#745):
 * Bare Witness's own folder and its Steam copy. The owner, 2026-10-29: "We should be able to link the copies, transfer
 * saves, and etc".
 *
 * Where each copy's saves are is [SaveDetector]'s answer, the one save-location mechanism: a copy's saves are the files
 * its rule matched ([FoundPlace.paths]) in its place with the newest saves, never anything else in that folder, so a
 * copy moves save files and never a game's own files. The target's saves are backed up first, into droidtop's own
 * files, and can be restored; the source is only read. A copy whose saves droidtop has not found has nowhere to copy
 * to: droidtop does not guess a folder for it.
 *
 * File work: every call runs on IO.
 */
object SaveCopies {
    /** Backups kept per copy; the oldest goes when one more is made (the agent core keeps five as well). */
    const val KEEP = 5

    private const val BACKUPS = "save-backups"
    private const val MANIFEST = "backup.txt"
    private const val FILES = "files"

    /** One copy's saves: the folder its newest saves are in, and the save files there. */
    data class Side(val entryId: String, val folder: File, val files: List<File>, val newestMs: Long)

    /** What a copy would do: each source file to where it lands, and which of those already exist and are replaced. */
    data class Plan(val copies: List<Pair<File, File>>, val replaced: List<File>)

    /** One backup of a copy's saves, taken before saves were copied over them. */
    data class Backup(val dir: File, val takenMs: Long, val folder: File, val files: Int)

    /** [status]'s place with the newest saves, as a [Side], or null when no saves were found. Pure. */
    fun sideOf(entryId: String, status: SaveStatus?): Side? {
        val found = status as? SaveStatus.Found ?: return null
        val place = found.places.filter { it.paths.isNotEmpty() }.maxByOrNull { it.newestMs } ?: return null
        val files = place.paths.map(::File)
        val folder = commonFolder(files) ?: return null
        return Side(entryId, folder, files, place.newestMs)
    }

    /** The deepest folder holding every one of [files], or null for none. Pure. */
    fun commonFolder(files: List<File>): File? {
        if (files.isEmpty()) return null
        var common = files.first().parentFile?.path?.split('/') ?: return null
        for (file in files.drop(1)) {
            val parts = file.parentFile?.path?.split('/') ?: return null
            common = common.zip(parts).takeWhile { (a, b) -> a == b }.map { it.first }
        }
        return common.joinToString("/").takeIf { it.isNotEmpty() }?.let(::File)
    }

    /** Where each of [from]'s files lands under [to]'s folder, keeping its path below [from]'s folder. Pure. */
    fun plan(from: Side, to: Side, exists: (File) -> Boolean = File::exists): Plan {
        val copies = from.files.mapNotNull { file -> relative(from.folder, file)?.let { file to File(to.folder, it) } }
        return Plan(copies, copies.map { it.second }.filter(exists))
    }

    /**
     * The engine both [a] and [b] run on, so their saves share its format, or null when droidtop cannot tell
     * (different engines, or not an engine game). Engine versions are not known per copy, so a major-version change
     * is not caught here; the copy is still the person's to make.
     */
    fun sameEngine(a: LibraryEntry, b: LibraryEntry): GameEngine? {
        val engine = GameEngine.entries.firstOrNull { it.toLibraryEntryKind() == a.kind } ?: return null
        return engine.takeIf { b.kind == a.kind }
    }

    /**
     * Copies [from]'s saves over [to]'s, after backing [to]'s saves up. The number of files copied. Throws when the
     * two are the same folder or one holds the other, or a file cannot be written; what was copied before the
     * failure stays, and the backup is there to restore.
     */
    suspend fun copy(context: Context, from: Side, to: Side): Int = copy(backupsOf(context), from, to)

    /** [copy], with backups kept under [backups] (droidtop's own files; a test's folder). */
    suspend fun copy(backups: File, from: Side, to: Side): Int = withContext(Dispatchers.IO) {
        val a = from.folder.canonicalFile
        val b = to.folder.canonicalFile
        require(a != b && !a.path.startsWith(b.path + "/") && !b.path.startsWith(a.path + "/")) {
            "The two copies keep their saves in the same place"
        }
        val plan = plan(from, to)
        backUp(backups, to, added = plan.copies.filterNot { it.second.exists() }.map { (source, target) -> target to source.lastModified() })
        for ((source, target) in plan.copies) {
            if (isLink(source) || isLink(target)) continue
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            target.setLastModified(source.lastModified())
        }
        plan.copies.size
    }

    /** [entryId]'s backups, newest first. */
    suspend fun backups(context: Context, entryId: String): List<Backup> = backups(backupsOf(context), entryId)

    /** [backups], kept under [backups]. */
    suspend fun backups(backups: File, entryId: String): List<Backup> = withContext(Dispatchers.IO) {
        val dirs = backupRoot(backups, entryId).listFiles()?.filter { it.isDirectory }.orEmpty()
        dirs.mapNotNull(::readBackup).sortedByDescending { it.takenMs }
    }

    /**
     * Puts [backup]'s files back where they were, and removes the files the copy added that are unchanged since
     * (a save the person made after the copy is kept). The number of files put back.
     */
    suspend fun restore(backup: Backup): Int = withContext(Dispatchers.IO) {
        val files = File(backup.dir, FILES)
        val saved = files.walkTopDown().filter { it.isFile }.toList()
        for (file in saved) {
            val rel = relative(files, file) ?: continue
            val target = File(backup.folder, rel)
            if (isLink(target)) continue
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
            target.setLastModified(file.lastModified())
        }
        for (line in File(backup.dir, MANIFEST).readLines().drop(2)) {
            val (stamp, path) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: continue
            val added = File(backup.folder, path)
            if (relative(backup.folder, added) != null && added.isFile && !isLink(added) && added.lastModified().toString() == stamp) added.delete()
        }
        saved.size
    }

    /**
     * Copies [side]'s save files into a new backup, writing its folder and the files the copy is about to add, and
     * keeps only the newest [KEEP].
     */
    private fun backUp(backups: File, side: Side, added: List<Pair<File, Long>>) {
        val root = backupRoot(backups, side.entryId)
        val dir = File(root, System.currentTimeMillis().toString())
        val files = File(dir, FILES)
        check(files.mkdirs()) { "Could not make a backup of the saves" }
        for (file in side.files) {
            val rel = relative(side.folder, file) ?: continue
            if (isLink(file) || !file.isFile) continue
            val copy = File(files, rel)
            copy.parentFile?.mkdirs()
            file.copyTo(copy, overwrite = true)
            copy.setLastModified(file.lastModified())
        }
        // Line 1: the folder; line 2: the copy's entry; then each file the copy adds, by the time it will carry.
        File(dir, MANIFEST).writeText(
            buildString {
                appendLine(side.folder.path)
                appendLine(side.entryId)
                for ((target, stamp) in added) relative(side.folder, target)?.let { rel -> appendLine("$stamp\t$rel") }
            },
        )
        root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name.toLongOrNull() ?: 0L }?.drop(KEEP)?.forEach { old ->
            // Only droidtop's own backup folders, which hold no links.
            old.walkBottomUp().forEach { it.delete() }
        }
    }

    private fun readBackup(dir: File): Backup? {
        val lines = runCatching { File(dir, MANIFEST).readLines() }.getOrNull() ?: return null
        val folder = lines.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        val count = File(dir, FILES).walkTopDown().count { it.isFile }
        return Backup(dir, dir.name.toLongOrNull() ?: dir.lastModified(), folder, count)
    }

    private fun backupsOf(context: Context): File = File(context.applicationContext.filesDir, BACKUPS)

    private fun backupRoot(backups: File, entryId: String): File = File(backups, sha256(entryId).take(24))

    /** [file]'s path below [folder], or null when it is not below it or the path climbs out. Pure. */
    fun relative(folder: File, file: File): String? {
        val base = folder.path.trimEnd('/') + "/"
        if (!file.path.startsWith(base)) return null
        val rel = file.path.removePrefix(base)
        return rel.takeIf { it.isNotEmpty() && rel.split('/').none { part -> part.isEmpty() || part == "." || part == ".." } }
    }

    private fun isLink(file: File): Boolean = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
