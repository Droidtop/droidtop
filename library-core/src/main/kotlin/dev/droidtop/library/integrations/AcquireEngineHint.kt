package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.EngineOverridePrefs
import dev.droidtop.library.EngineRegistryParser
import java.io.File

/**
 * A source's engine hint (docs/plugin-api.md 1.6, the acquire reply's `engine`): the engines-database id of the engine
 * the source says a download's game uses. Applied when the download job places the file, before the library looks at it
 * (`DownloadJobs.onPlaced`), as the same pin the person sets on a game's Engine row ([EngineOverridePrefs]), so the
 * first index already shows the engine and launches with its Enginehost player.
 */
object AcquireEngineHint {
    /** The job argument, and the acquire reply value, that carry the hint. */
    const val KEY = "engine"

    private val ARCHIVE = Regex("""(?i)\.(zip|7z|rar|tar|tar\.gz|tgz|tar\.xz|txz|tar\.bz2)$""")

    /** [raw] when it is an engine id this build knows, else null: an unknown id is no hint. */
    fun valid(raw: String?): String? = raw?.trim()?.takeIf { it in EngineRegistryParser.ENGINE_IDS }

    /**
     * The paths a hint pins for a file placed at [placed]: the file or folder itself, and for an archive also the folder
     * it unpacks to beside it (its name less the archive extension). Pure, for the tests.
     */
    fun pinnedPaths(placed: File): List<String> = buildList {
        add(placed.absolutePath)
        ARCHIVE.find(placed.name)?.let { add(File(placed.parentFile, placed.name.substring(0, it.range.first)).absolutePath) }
    }.distinct()

    fun apply(context: Context, placed: File, engine: String?) {
        val id = valid(engine) ?: return
        for (path in pinnedPaths(placed)) {
            // A pin the person already set wins over a source's word.
            if (EngineOverridePrefs.get(context, path) == null) EngineOverridePrefs.set(context, path, id)
        }
    }
}
