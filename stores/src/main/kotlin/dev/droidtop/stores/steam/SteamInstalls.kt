package dev.droidtop.stores.steam

import java.io.File

/**
 * What an install says about itself on disk. The depot downloader keeps the
 * manifest of every depot build it installed in the game's folder, as
 * `.DepotDownloader/<depot>_<manifest gid>.manifest` (GameNative read the
 * same files to find a game's program); droidtop reads the installed build of
 * each depot from those names, so an install GameNative made and one droidtop
 * made answer the same way, with nothing kept beside the files.
 */
internal object SteamInstalls {
    /** The depot downloader's own folder in a game's folder (DepotDownloader.CONFIG_DIR). */
    const val CONFIG_DIR = ".DepotDownloader"

    private val MANIFEST_NAME = Regex("""^(\d+)_(-?\d+)\.manifest$""")

    /**
     * One manifest file's depot and build, or null for any other name. A
     * build id is an unsigned 64-bit number kept in a signed Long, so its
     * name may be written either way; both read as the same Long.
     */
    fun parse(fileName: String): Pair<Int, Long>? {
        val match = MANIFEST_NAME.matchEntire(fileName) ?: return null
        val depot = match.groupValues[1].toIntOrNull() ?: return null
        val text = match.groupValues[2]
        val gid = text.toLongOrNull() ?: text.toULongOrNull()?.toLong() ?: return null
        return depot to gid
    }

    /**
     * The installed build of each depot among [files] (name and modification
     * time): the newest manifest of a depot wins, since an update leaves the
     * old one beside it.
     */
    fun installedBuilds(files: List<Pair<String, Long>>): Map<Int, Long> =
        files.mapNotNull { (name, modified) -> parse(name)?.let { Triple(it.first, it.second, modified) } }
            .groupBy { it.first }
            .mapValues { (_, builds) -> builds.maxBy { it.third }.second }

    /** [installedBuilds] of the game in [installDir]; one listing. */
    fun installedBuilds(installDir: File): Map<Int, Long> {
        val files = File(installDir, CONFIG_DIR).listFiles()?.map { it.name to it.lastModified() } ?: return emptyMap()
        return installedBuilds(files)
    }

    /** The manifest file of [depot] at build [gid] in [installDir], whichever way its build id is written. */
    fun manifestFile(installDir: File, depot: Int, gid: Long): File {
        val dir = File(installDir, CONFIG_DIR)
        val signed = File(dir, "${depot}_$gid.manifest")
        return if (signed.isFile || gid >= 0) signed else File(dir, "${depot}_${gid.toULong()}.manifest")
    }

    /**
     * Whether an installed game is behind: some depot it has a build of is
     * served at another build on [branch] now. A depot Steam no longer names
     * on the branch is passed over (GameNative's Castle Crashers rule); null
     * when nothing installed can be compared.
     */
    fun isBehind(installed: Map<Int, Long>, live: Map<Int, DepotInfo>, branch: String): Boolean? {
        val compared = installed.mapNotNull { (depot, gid) ->
            val remote = live[depot]?.manifests?.get(branch) ?: return@mapNotNull null
            remote.gid != gid
        }
        return if (compared.isEmpty()) null else compared.any { it }
    }

    /**
     * Where a game GameNative installed is: the folder it was pointed at, or
     * the first of [roots] (its Steam install folders) holding a folder of
     * one of [names], a finished install ([isFinished]) before a partial one
     * (GameNative's resolveExistingAppDir). Null when none is there.
     */
    fun findInstall(customPath: String, roots: List<File>, names: List<String>, isFinished: (File) -> Boolean): File? {
        if (customPath.isNotBlank()) return File(customPath).takeIf { it.isDirectory }
        var firstExisting: File? = null
        for (root in roots) {
            for (name in names.filter { it.isNotBlank() }.distinct()) {
                val candidate = File(root, name)
                if (!candidate.isDirectory) continue
                if (isFinished(candidate)) return candidate
                if (firstExisting == null) firstExisting = candidate
            }
        }
        return firstExisting
    }
}
