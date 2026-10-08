package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.LibraryPaths
import dev.droidtop.library.settings.LibraryRescan
import dev.droidtop.library.settings.PathChange
import java.io.File
import org.json.JSONArray

/**
 * How a source plugin's `acquire` job that wrote into the destination itself
 * (it returned no `download`, so droidtop did not place anything) reaches the
 * library (docs/SPEC.md 7g, "Targeted indexing"; docs/plugin-api.md 3 A2).
 *
 * In order of what is known:
 *  1. The job's reply names what it wrote (`placed`, a JSON list of paths, or
 *     the older `filePath`): exactly those files, when they lie inside the
 *     destination, are reported and nothing is walked.
 *  2. The plugin reported by itself during the job (`library.files` `changed`):
 *     nothing more to do.
 *  3. The plugin said nothing about what it wrote, so droidtop cannot name a
 *     file: the one case left in which the library is walked again, in the
 *     background, as it was for every download before. A plugin avoids it by
 *     doing 1 or 2.
 *
 * This runs from the job's own completion, not from the screen that started
 * it, so a download that finishes after that screen is closed still arrives.
 */
object AcquireIndexing {
    /** Blocking file IO: not for the main thread. */
    fun afterAcquire(context: Context, pluginId: String, startedAtMs: Long, destination: File?, values: Map<String, String>) {
        val inside = destination?.let { folder ->
            val named = placedPaths(values, folder)
            named.filter { LibraryPaths.outside(listOf(it), listOf(folder.absolutePath)).isEmpty() }
        }.orEmpty()
        when {
            inside.isNotEmpty() -> LibraryPaths.report(context, PathChange(added = inside))
            LibraryPaths.reportedSince(pluginId, startedAtMs) -> Unit
            else -> LibraryRescan.requestInBackground(context)
        }
    }

    /**
     * The paths a job reply names: `placed` (a JSON list, each path absolute or relative to [destination])
     * and `filePath`. Paths are returned as absolute and unchecked; the caller keeps those inside the
     * destination.
     */
    internal fun placedPaths(values: Map<String, String>, destination: File): List<String> {
        fun absolute(path: String) = if (path.startsWith("/")) path else File(destination, path).path
        val listed = values["placed"]?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                List(array.length()) { array.optString(it) }
            }.getOrDefault(emptyList())
        }.orEmpty()
        return (listed + listOfNotNull(values["filePath"])).filter { it.isNotBlank() }.map(::absolute).distinct()
    }
}
