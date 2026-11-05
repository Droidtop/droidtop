package dev.droidtop.stores.util

import dev.droidtop.library.stores.SaveConflict
import dev.droidtop.library.stores.SaveConflictResolver
import dev.droidtop.library.stores.SaveSyncResult
import dev.droidtop.stores.util.CloudSavePlan.Decision
import dev.droidtop.stores.util.CloudSavePlan.Facts
import java.io.File
import java.util.Locale

/**
 * What GOG's and Epic's cloud save syncs share around [CloudSavePlan] (docs/SPEC.md 7g, "Stores",
 * Droidtop/tracker#224): the decision with the person asked on a conflict, the folders a store names walked into the
 * cloud's names, a cloud name turned back into a file that stays inside its folder, and the one line each outcome
 * reads as.
 */
internal object CloudSaveRun {
    /** How deep a save folder is walked; Steam Auto-Cloud's own limit, which no store's saves come near. */
    const val MAX_DEPTH = 8

    /**
     * The plan for [local] and [remote] from [state], with a conflict put to the person through [onConflict]. A null
     * decision comes with the result to report: nobody to ask, or "Decide later", so nothing changes.
     */
    suspend fun decide(
        state: CloudSaveState,
        local: Map<String, Facts>,
        remote: Map<String, Facts>,
        title: String,
        cloudLabel: String,
        onConflict: SaveConflictResolver?,
    ): Pair<Decision?, SaveSyncResult?> {
        val decision = CloudSavePlan.decide(state.base, local, remote)
        if (decision !is Decision.Conflict) return decision to null
        val choice = onConflict?.resolve(title, SaveConflict(decision.local.asSaveSide(), decision.cloud.asSaveSide(), cloudLabel))
            ?: return null to SaveSyncResult("Saves differ between this device and $cloudLabel. Choose which to keep", unresolved = true)
        return CloudSavePlan.settle(choice, local, remote) to null
    }

    /** One save folder a store names: [name] is the cloud's prefix for its files ("" for none), [dir] the folder here. */
    data class Folder(val name: String, val dir: File)

    /** The files under [folders] keyed by the cloud's name for each (`<folder name>/<path>`), with the file. */
    fun scan(folders: List<Folder>): Map<String, File> {
        val found = LinkedHashMap<String, File>()
        for (folder in folders) {
            if (!folder.dir.isDirectory) continue
            folder.dir.walkTopDown().maxDepth(MAX_DEPTH).filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) }.forEach { file ->
                val rel = file.relativeTo(folder.dir).invariantSeparatorsPath
                found.putIfAbsent(join(folder.name, rel), file)
            }
        }
        return found
    }

    /** The cloud's name of [rel] in the folder [name]. */
    fun join(name: String, rel: String): String = if (name.isEmpty()) rel else "$name/$rel"

    /**
     * Where the cloud's file [key] goes on this device: under the folder whose name starts it (the longest such name;
     * the unnamed folder takes the rest), each part matched to a folder or file already there whatever its case, as
     * Windows would. Null when no folder takes it or it would leave its folder (`..`, an absolute path).
     */
    fun localFile(folders: List<Folder>, key: String): File? {
        val folder = folders.filter { it.name.isNotEmpty() && key.startsWith(it.name + "/") }.maxByOrNull { it.name.length }
            ?: folders.firstOrNull { it.name.isEmpty() }
            ?: return null
        val rel = if (folder.name.isEmpty()) key else key.removePrefix(folder.name + "/")
        val parts = rel.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." } || rel.startsWith("/") || parts.first().contains(':')) return null
        var at = folder.dir
        for (part in parts) {
            at = at.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: File(at, part)
        }
        return at
    }

    /** The file a download is written to before it replaces [target]. */
    fun tempFor(target: File): File = File(target.parentFile, ".${target.name}$TEMP_SUFFIX")

    /** Puts [temp] in place of [target] with the cloud's [timeMs]; false when it could not. */
    fun replace(temp: File, target: File, timeMs: Long): Boolean {
        if (timeMs > 0) temp.setLastModified(timeMs)
        if (temp.renameTo(target)) return true
        return runCatching {
            temp.copyTo(target, overwrite = true)
            if (timeMs > 0) target.setLastModified(timeMs)
            true
        }.getOrDefault(false).also { temp.delete() }
    }

    private const val TEMP_SUFFIX = ".droidtop-cloud"

    fun upToDate(): SaveSyncResult = SaveSyncResult("Saves are up to date")

    fun downloaded(cloudLabel: String, done: Int, removed: Int, failed: Int, total: Int): SaveSyncResult = SaveSyncResult(
        line = if (failed > 0) "Cloud saves: $failed of $total files could not be updated" else "Saves updated from $cloudLabel ($done)",
        downloaded = done,
        removed = removed,
        failed = failed > 0,
    )

    fun uploaded(cloudLabel: String, done: Int, removed: Int, failed: Int, total: Int): SaveSyncResult = SaveSyncResult(
        line = if (failed > 0) "Cloud saves: $failed of $total files could not be sent" else "Saves sent to $cloudLabel ($done)",
        uploaded = done,
        removed = removed,
        failed = failed > 0,
    )

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}
