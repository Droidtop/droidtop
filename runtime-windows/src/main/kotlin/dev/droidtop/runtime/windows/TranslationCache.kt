package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.xenvironment.ImageFs
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.shared.trust.Sha256
import java.io.File

/**
 * The translators' code caches, one per game (docs/SPEC.md 9, "Translation cache"; Droidtop/tracker#865).
 *
 * Box64 keeps the native code it generated on disk from 0.3.8 (DynaCache: `BOX64_DYNACACHE`, `_FOLDER`, `_LIMIT`), FEX
 * from 2609 (DiskCache: `FEX_DISKCACHE`, `FEX_DISKCACHEPATH`), in its Windows ARM64EC and WOW64 modules too. Older
 * builds ignore the variables. WowBox64 compiles DynaCache out, so 32-bit code it runs is not cached.
 *
 * Each game's cache is a folder of its own under the ImageFs root, which Wine sees as Z: (WineUtils): Box64 reads a
 * unix path, FEX inside Wine a Windows one, so both reach the same folder. It lives in droidtop's own storage, never in
 * the game's folder. Box64 trims its own folder to [GAME_LIMIT_BYTES]; FEX has no limit of its own, so [prepare] trims
 * the game's folder before each launch, and every cache together to [TOTAL_LIMIT_BYTES], oldest files first.
 */
object TranslationCache {
    /** One game's cache, both translators together: 1 GB, in the decimal units droidtop shows sizes in. */
    const val GAME_LIMIT_BYTES = 1_000_000_000L

    /** Every game's cache together: 4 GB. */
    const val TOTAL_LIMIT_BYTES = 4_000_000_000L

    private const val DIR = "translation-cache"
    private const val KEY_ENABLED = "droidtop_translation_cache"
    private const val MIB = 1024L * 1024L

    /** On for every game unless turned off in Settings > Windows games. Preferences read. */
    fun enabled(context: Context): Boolean = CatalogPrefs.prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        CatalogPrefs.prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun root(context: Context): File = root(ImageFs.find(context).rootDir)

    internal fun root(imageFsRoot: File): File = File(imageFsRoot, DIR)

    /** A folder name for [entryId]: library ids carry characters a path must not. */
    internal fun key(entryId: String): String =
        Sha256.hex(entryId.toByteArray()).take(24)

    internal fun gameDir(imageFsRoot: File, entryId: String): File = File(root(imageFsRoot), key(entryId))

    /**
     * The variables that turn both caches on, pointed at [gameDir], which lies under [imageFsRoot] (Wine's Z:).
     * Forward slashes in the Windows path: FEX hands it to std::filesystem, which takes either.
     */
    internal fun launchEnv(imageFsRoot: File, gameDir: File, limitMiB: Long = GAME_LIMIT_BYTES / MIB): Map<String, String> {
        val underZ = gameDir.relativeTo(imageFsRoot).invariantSeparatorsPath
        return linkedMapOf(
            "BOX64_DYNACACHE" to "1",
            "BOX64_DYNACACHE_FOLDER" to File(gameDir, "box64").absolutePath,
            "BOX64_DYNACACHE_LIMIT" to limitMiB.toString(),
            "FEX_DISKCACHE" to "1",
            "FEX_DISKCACHEPATH" to "Z:/$underZ/fex/",
        )
    }

    /**
     * Before [entryId]'s launch: makes its folder, trims it and all caches to their limits, and returns the variables
     * as `NAME=value` words for the launch environment, or null when the cache is off for every game or for this one
     * ([gameOn]). Disk work: never on the main thread.
     */
    fun prepare(context: Context, entryId: String?, gameOn: Boolean): String? {
        if (entryId == null || !gameOn || !enabled(context)) return null
        val imageFsRoot = ImageFs.find(context).rootDir
        val dir = gameDir(imageFsRoot, entryId)
        File(dir, "box64").mkdirs()
        File(dir, "fex").mkdirs()
        trim(listOf(dir), GAME_LIMIT_BYTES)
        trim(root(imageFsRoot).listFiles()?.toList().orEmpty(), TOTAL_LIMIT_BYTES)
        return launchEnv(imageFsRoot, dir).entries.joinToString(" ") { (name, value) -> "$name=$value" }
    }

    /** Bytes [entryId]'s cache takes, or every game's when null. Disk work. */
    fun size(context: Context, entryId: String?): Long {
        val imageFsRoot = ImageFs.find(context).rootDir
        val dir = if (entryId == null) root(imageFsRoot) else gameDir(imageFsRoot, entryId)
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /** Deletes [entryId]'s cache, or every game's when null. The next start of a game translates afresh. Disk work. */
    fun clear(context: Context, entryId: String?) {
        val imageFsRoot = ImageFs.find(context).rootDir
        (if (entryId == null) root(imageFsRoot) else gameDir(imageFsRoot, entryId)).deleteRecursively()
    }

    /** Deletes the oldest files under [dirs] until together they take at most [limitBytes]. */
    internal fun trim(dirs: List<File>, limitBytes: Long) {
        val files = dirs.flatMap { dir -> dir.walkBottomUp().filter { it.isFile }.toList() }
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= limitBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
}
