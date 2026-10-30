package dev.droidtop.runtime

/**
 * The commands a root container's copy and backup run as root (docs/SPEC.md 3d "Backup" and "Copy";
 * Droidtop/tracker#758): Android's own toybox `tar`, `find` and `cp`, as argv, never a shell line. Pure; the root
 * backend runs them through [RootProcess].
 */
object RootTar {
    private const val TAR = "/system/bin/tar"
    private const val FIND = "/system/bin/find"
    private const val CP = "/system/bin/cp"

    /** The find that lists the sockets in [rootfs], one path a line, not crossing into other file systems. */
    fun socketsCommand(rootfs: String): List<String> = listOf(FIND, rootfs, "-xdev", "-type", "s")

    /**
     * [findOutput] from [socketsCommand] as tar's excludes, member names spelled "./..." as toybox matches them. A
     * socket is dead state in a stopped container and unknown to tar, so it is left out.
     */
    fun socketExcludes(rootfs: String, findOutput: String): List<String> {
        val prefix = rootfs.trimEnd('/') + "/"
        return findOutput.lineSequence().map { it.trim() }.filter { it.startsWith(prefix) }
            .map { "--exclude=./" + it.removePrefix(prefix) }.toList()
    }

    /** The tar that writes [rootfs] to its stdout, entries relative to its root, leaving out [excludes]. */
    fun exportCommand(rootfs: String, excludes: List<String>): List<String> =
        listOf(TAR, "-cf", "-") + excludes + listOf("-C", rootfs, ".")

    /** The tar that unpacks its stdin into [into]. Toybox drops a leading "/" and refuses ".." members. */
    fun importCommand(into: String): List<String> = listOf(TAR, "-xf", "-", "-C", into)

    /** The copy of [rootfs] to [into], which must not exist: owners, modes, links and special files kept. */
    fun copyCommand(rootfs: String, into: String): List<String> = listOf(CP, "-a", rootfs, into)
}
