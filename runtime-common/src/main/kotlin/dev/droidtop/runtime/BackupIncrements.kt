package dev.droidtop.runtime

/**
 * Incremental container backups (docs/SPEC.md 3d "Backups on a schedule", Droidtop/tracker#808 slice B2), with
 * Android's own toybox `find` and `tar` as argv, on both backends. An increment is two files: an archive of the files
 * changed since the backup before it, and a manifest of every path present, so a restore puts back what changed and
 * removes what was deleted. Directories are listed in the manifest, not archived, so a changed directory does not
 * drag its whole contents in. Pure; the backends run the commands.
 */
object BackupIncrements {
    private const val TAR = "/system/bin/tar"
    private const val FIND = "/system/bin/find"

    /** After this many increments the next scheduled backup is a full one again. */
    const val INCREMENTS_PER_CHAIN = 6

    /** Every directory in [rootfs], one path a line, not crossing into other file systems. */
    fun directoriesCommand(rootfs: String): List<String> = listOf(FIND, rootfs, "-xdev", "-type", "d")

    /** Every other entry but sockets, which are dead state in a stopped container. */
    fun entriesCommand(rootfs: String): List<String> = listOf(FIND, rootfs, "-xdev", "!", "-type", "d", "!", "-type", "s")

    /**
     * The entries changed within the last [minutes]: newer than the backup before, with a minute's margin so a file
     * written as that backup ran is taken again rather than missed.
     */
    fun changedCommand(rootfs: String, minutes: Long): List<String> =
        entriesCommand(rootfs) + listOf("-mmin", "-${minutes.coerceAtLeast(1) + 1}")

    /** The tar that writes the entries named in [listFile] (relative, "./..."), and only them, to its stdout. */
    fun archiveListCommand(rootfs: String, listFile: String): List<String> =
        listOf(TAR, "-cf", "-", "--no-recursion", "-C", rootfs, "-T", listFile)

    /** The tar that unpacks an increment over [into], replacing what it holds. */
    fun applyCommand(into: String): List<String> = listOf(TAR, "-xf", "-", "-C", into)

    /** [findOutput]'s absolute paths under [rootfs] as tar member names, "./..."; the root itself left out. */
    fun relative(rootfs: String, findOutput: String): List<String> {
        val prefix = rootfs.trimEnd('/') + "/"
        return findOutput.lineSequence().map { it.trim() }.filter { it.startsWith(prefix) }.map { "./" + it.removePrefix(prefix) }.toList()
    }

    /** A manifest: one line an entry, "d ./path" for a directory and "f ./path" for anything else. */
    fun manifest(directories: List<String>, entries: List<String>): String =
        (directories.map { "d $it" } + entries.map { "f $it" }).sorted().joinToString("\n", postfix = "\n")

    data class Manifest(val directories: Set<String>, val entries: Set<String>) {
        val all: Set<String> get() = directories + entries
    }

    fun parse(text: String): Manifest {
        val dirs = HashSet<String>()
        val files = HashSet<String>()
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("d ") -> dirs += line.substring(2)
                line.startsWith("f ") -> files += line.substring(2)
            }
        }
        return Manifest(dirs, files)
    }

    /**
     * What a restore removes after unpacking: every path present now that [wanted] does not hold, deepest first so a
     * directory goes after what was in it. A path outside the tree ("..") is never named.
     */
    fun toRemove(present: Manifest, wanted: Manifest): List<String> =
        (present.all - wanted.all).filter { ".." !in it.split('/') }
            .sortedWith(compareByDescending<String> { it.count { c -> c == '/' } }.thenBy { it })

    /** The directories [wanted] holds that are not there now: empty ones an archive of files cannot bring back. */
    fun toMake(present: Manifest, wanted: Manifest): List<String> =
        (wanted.directories - present.directories).filter { ".." !in it.split('/') }
            .sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it })
}

/**
 * One backend's way of running [BackupIncrements]' commands: as droidtop itself (proot, its own tree) or as root
 * (droidspaces, through the root shell). Every call blocks: IO only. Each throws with the tool's own words on failure.
 */
interface IncrementTools {
    /** Runs [argv] and gives its standard output. */
    fun run(argv: List<String>): String

    /** Runs [argv] and copies its standard output to [out]. */
    fun pipeOut(argv: List<String>, out: java.io.OutputStream)

    /** Runs [argv] with [input] as its standard input. */
    fun pipeIn(argv: List<String>, input: java.io.InputStream)

    /** Removes these absolute paths, each a file, link or empty directory. */
    fun remove(paths: List<String>)

    /** Makes these absolute directories. */
    fun makeDirectories(paths: List<String>)

    /** A scratch file the tools can read, for tar's list. */
    fun scratchFile(): java.io.File
}

/** [BackupIncrements] run through a backend's [IncrementTools]. */
class IncrementalArchiver(private val rootfs: String, private val tools: IncrementTools) {
    private fun manifestNow(): BackupIncrements.Manifest {
        val dirs = BackupIncrements.relative(rootfs, tools.run(BackupIncrements.directoriesCommand(rootfs)))
        val entries = BackupIncrements.relative(rootfs, tools.run(BackupIncrements.entriesCommand(rootfs)))
        return BackupIncrements.Manifest(dirs.toSet(), entries.toSet())
    }

    /** Writes the entries changed in the last [minutes] to [archive] and the manifest of every path to [manifest]. */
    fun exportChanged(minutes: Long, archive: java.io.OutputStream, manifest: java.io.OutputStream) {
        val now = manifestNow()
        manifest.write(BackupIncrements.manifest(now.directories.toList(), now.entries.toList()).toByteArray())
        manifest.flush()
        val changed = BackupIncrements.relative(rootfs, tools.run(BackupIncrements.changedCommand(rootfs, minutes)))
        val list = tools.scratchFile()
        try {
            list.writeText(changed.joinToString("\n", postfix = "\n"))
            tools.pipeOut(BackupIncrements.archiveListCommand(rootfs, list.absolutePath), archive)
        } finally {
            list.delete()
        }
    }

    /** Unpacks one increment's [archive] over the tree. */
    fun apply(archive: java.io.InputStream) = tools.pipeIn(BackupIncrements.applyCommand(rootfs), archive)

    /** Makes the tree hold exactly the paths in [manifest]: what it does not list goes, empty directories it lists come back. */
    fun pruneTo(manifest: String) {
        val wanted = BackupIncrements.parse(manifest)
        val present = manifestNow()
        val base = rootfs.trimEnd('/')
        fun absolute(member: String) = base + "/" + member.removePrefix("./")
        tools.remove(BackupIncrements.toRemove(present, wanted).map(::absolute))
        tools.makeDirectories(BackupIncrements.toMake(present, wanted).map(::absolute))
    }
}
