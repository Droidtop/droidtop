package dev.droidtop.stores.steam

import dev.droidtop.stores.util.StoreFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The names Steam Cloud files go by, in one canonical spelling: the root's
 * token and the path under it with forward slashes (`%WinAppDataLocal%Game/save1.dat`),
 * or only the path for a file in the game's `remote` folder (the files the
 * Steam API writes), which Steam names without a root. Steam hands a name over
 * as a path prefix and a file name; where the two meet is not always a slash
 * (`%GameInstall%` + `save0.dat`, `%WinAppDataLocal%Game` + `x.dat`).
 */
internal object CloudKey {
    private val TOKEN = Regex("^%(\\w+)%")
    private val BARE_TOKEN = Regex("^%\\w+%$")

    /** A root and the path under it. */
    data class Parsed(val root: SaveRoot, val segments: List<String>) {
        /** The name Steam wants when a file is uploaded. */
        val name: String
            get() {
                val path = segments.joinToString("/")
                return if (root == SaveRoot.SteamUserData) path else root.token + path
            }

        /** The name compared the way Windows compares paths. */
        val key: String get() = name.lowercase()
    }

    /** The full name of a file Steam lists as [prefix] (maybe empty) and [filename]. */
    fun join(prefix: String, filename: String): String = when {
        prefix.isBlank() -> filename
        // Steam sometimes puts the whole name in the file name and no prefix at all.
        TOKEN.containsMatchIn(filename) -> filename
        BARE_TOKEN.matches(prefix) -> prefix + filename
        prefix.endsWith('/') || prefix.endsWith('\\') -> prefix + filename
        else -> "$prefix/$filename"
    }

    fun parse(full: String): Parsed {
        val text = full.replace('\\', '/')
        val match = TOKEN.find(text)
        val root = if (match == null) SaveRoot.SteamUserData else SaveRoot.from(match.groupValues[1])
        val rest = if (match == null) text else text.substring(match.range.last + 1)
        return Parsed(root, rest.split('/').filter { it.isNotEmpty() && it != "." })
    }

    /** The name for [root] and [segments]. */
    fun of(root: SaveRoot, segments: List<String>): Parsed = Parsed(root, segments.filter { it.isNotEmpty() && it != "." })
}

/**
 * The folders of one game's save roots on this device, and the mapping
 * between Steam's cloud names and files there (the mapping Steam Auto-Cloud
 * defines with `savefiles` and `rootoverrides`, GameNative's SteamAutoCloud
 * prefix handling, GPL-3.0).
 *
 * [dirs] holds the host folder of every Windows root the game's Wine prefix
 * has; a root with no entry is not there to read or write.
 */
internal class SaveLayout(
    private val dirs: Map<SaveRoot, File>,
    private val steamId64: Long,
    private val accountId: Long,
    private val ufs: SteamUfs,
) {
    private val byCloudRoot = ufs.patterns.groupBy { it.uploadRoot }

    /** A pattern's path with the account's ids put in and slashes made forward. */
    fun substitute(path: String): String =
        path.replace("{64BitSteamID}", steamId64.toString())
            .replace("{Steam3AccountID}", accountId.toString())
            .replace('\\', '/')
            .trim('/')

    private fun segmentsOf(path: String): List<String> = substitute(path).split('/').filter { it.isNotEmpty() && it != "." }

    /** The folder a pattern reads on this device, or null when its root is not in the prefix. */
    private fun folderOf(pattern: SavePattern): File? {
        val root = dirs[pattern.root] ?: return null
        return StoreFiles.resolveCaseInsensitive(root, substitute(pattern.path))
    }

    /**
     * The file on this device a cloud file named [name] lives at, or null when
     * the name's root has no folder here. The pattern whose cloud path is the
     * longest start of the name decides where; with none, the root's folder
     * with the same path under it.
     */
    fun localFile(name: CloudKey.Parsed): File? {
        val candidates = byCloudRoot[name.root].orEmpty().mapNotNull { pattern ->
            val cloud = segmentsOf(pattern.uploadPath)
            if (name.segments.size >= cloud.size && name.segments.take(cloud.size).map { it.lowercase() } == cloud.map { it.lowercase() }) {
                pattern to cloud.size
            } else {
                null
            }
        }
        val best = candidates.maxByOrNull { it.second }
        if (best != null) {
            val folder = folderOf(best.first) ?: return null
            return StoreFiles.resolveCaseInsensitive(folder, name.segments.drop(best.second).joinToString("/"))
        }
        val root = dirs[name.root] ?: return null
        return StoreFiles.resolveCaseInsensitive(root, name.segments.joinToString("/"))
    }

    /** One file on this device with the cloud name it goes by. */
    data class Local(val name: CloudKey.Parsed, val file: File)

    /**
     * Every save file on this device: what each pattern matches in its folder,
     * everything in the game's `remote` folder, and the files at the places
     * [alsoNamed] (the names the cloud or the last sync know) map to, so a file
     * the patterns would not find is still compared once it is known.
     */
    fun scan(alsoNamed: Collection<String>): List<Local> {
        val found = LinkedHashMap<String, Local>()
        fun add(local: Local) {
            found.putIfAbsent(local.name.key, local)
        }
        for (pattern in ufs.patterns) {
            val folder = folderOf(pattern)?.takeIf { it.isDirectory } ?: continue
            val matcher = glob(pattern.pattern)
            val cloudBase = segmentsOf(pattern.uploadPath)
            for ((relative, file) in filesUnder(folder, if (pattern.recursive != 0) MAX_DEPTH else 1)) {
                val last = relative.last()
                val matches = if ('/' in pattern.pattern) matcher.matches(relative.joinToString("/")) else matcher.matches(last)
                if (matches) add(Local(CloudKey.of(pattern.uploadRoot, cloudBase + relative), file))
            }
        }
        dirs[SaveRoot.SteamUserData]?.takeIf { it.isDirectory }?.let { root ->
            for ((relative, file) in filesUnder(root, MAX_DEPTH)) add(Local(CloudKey.of(SaveRoot.SteamUserData, relative), file))
        }
        for (full in alsoNamed) {
            val name = CloudKey.parse(full)
            if (name.key in found) continue
            val file = localFile(name)?.takeIf { it.isFile } ?: continue
            add(Local(name, file))
        }
        return found.values.toList()
    }

    private fun filesUnder(folder: File, maxDepth: Int): List<Pair<List<String>, File>> {
        val base: Path = runCatching { folder.canonicalFile.toPath() }.getOrNull() ?: return emptyList()
        return runCatching {
            Files.walk(base, maxDepth).use { stream ->
                stream.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
                    .map { path -> base.relativize(path).map { it.toString() } to path.toFile() }
                    .collect(java.util.stream.Collectors.toList())
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** Steam Auto-Cloud searches at most this deep (GameNative's choice). */
        const val MAX_DEPTH = 5

        /**
         * The folders of the Windows roots in a Wine prefix: [prefixDir] holds
         * `drive_c`, [user] is the Windows user in it, [installDir] is where the
         * game is. `SteamUserData` is the `remote` folder Steam's own client
         * keeps (and GameNative put in its prefixes), where the files the Steam
         * API writes live.
         */
        fun windowsDirs(prefixDir: File, user: String, installDir: File, accountId: Long, appId: Int): Map<SaveRoot, File> {
            val drive = File(prefixDir, "drive_c")
            val home = File(drive, "users/$user")
            return mapOf(
                SaveRoot.GameInstall to installDir,
                SaveRoot.SteamUserData to File(drive, "Program Files (x86)/Steam/userdata/$accountId/$appId/remote"),
                SaveRoot.WinMyDocuments to File(home, "Documents"),
                SaveRoot.WinAppDataLocal to File(home, "AppData/Local"),
                SaveRoot.WinAppDataLocalLow to File(home, "AppData/LocalLow"),
                SaveRoot.WinAppDataRoaming to File(home, "AppData/Roaming"),
                SaveRoot.WinSavedGames to File(home, "Saved Games"),
                SaveRoot.WinProgramData to File(drive, "ProgramData"),
                SaveRoot.Root to home,
            )
        }

        /** A save pattern (`*.sav`, `slot?.dat`) as a case-insensitive whole-name match. */
        fun glob(pattern: String): Regex {
            val text = pattern.ifBlank { "*" }
            val regex = buildString {
                for (c in text) {
                    when (c) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        else -> append(Regex.escape(c.toString()))
                    }
                }
            }
            return Regex("^$regex$", RegexOption.IGNORE_CASE)
        }
    }
}
