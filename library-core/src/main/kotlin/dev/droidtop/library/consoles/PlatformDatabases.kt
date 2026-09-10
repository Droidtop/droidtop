package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.EnginesDatabase
import org.json.JSONObject

/**
 * One entry point for "bring the platform databases up to date"
 * (docs/SPEC.md 7e2), used by both the manual settings action and the
 * scheduled update pass, so the two can never do different things.
 *
 * Preferred path is the index ([PlatformDatabaseIndex]): one small
 * document, then only the per-file changes. The whole-file path is kept as
 * the fallback for a source that publishes no index -- an older
 * droidtop-platforms commit, or a fork or mirror that only keeps the four
 * monolithic files. The fallback is expected to become dead once every
 * published source carries an index; it is not speculation, it is what the
 * default source itself served until this change.
 */
object PlatformDatabases {
    /** Runs a refresh and returns the one-line summary the settings row shows. Throws on failure. */
    fun refresh(context: Context, onStatus: (String) -> Unit = {}): String {
        val baseUrl = PlatformDatabaseSource.baseUrl(context)
        PlatformDatabaseIndex.refresh(context, baseUrl, onStatus)?.let { result ->
            val counts = result.counts.entries.joinToString(", ") { (name, count) -> "$count $name" }
            return "Updated: " + counts + " (" + result.downloaded + " files changed, " +
                result.unchanged + " already current)"
        }
        onStatus("Updating players...")
        val players = PlayersDatabaseUpdater.update(context)
        onStatus("Updating platforms...")
        val platforms = PlatformsDatabase.update(context)
        onStatus("Updating engine routing...")
        val engines = EnginesDatabase.update(context)
        onStatus("Updating BIOS registry...")
        val bios = BiosDatabase.update(context)
        return "Updated: $players players, $platforms platforms, $engines engines, $bios BIOS systems " +
            "(whole-file: this source publishes no index)"
    }
}

/**
 * Which droidtop-platforms commit this build's bundled databases ARE
 * (docs/SPEC.md 7e2). The seed is copied from a pinned submodule at build
 * time and the commit written beside it, so the settings screen can name
 * the snapshot instead of leaving the user to guess how old four
 * hand-copied JSONs were -- which is the drift this replaced.
 */
object PlatformDatabaseSnapshot {
    private const val ASSET_NAME = "platform-database-snapshot.json"

    /** The full commit hash, or null in a build whose seed was copied without git. */
    fun commit(context: Context): String? = runCatching {
        JSONObject(context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() })
            .optString("commit", "")
            .takeIf { it.isNotEmpty() && it != "unknown" }
    }.getOrNull()

    /** The short form used in UI text. */
    fun shortCommit(context: Context): String? = commit(context)?.take(7)
}
