package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.EnginesDatabase
import java.io.File
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
        // A source without an index has no publish time: what it serves is taken as current.
        PlatformDatabaseSnapshot.noteRefreshed(context, "")
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

    private const val REFRESHED_MARKER = "platform-db/refreshed-generatedAt"

    /** When the platform index this build's seed came from was generated (ISO-8601 UTC), or "" when unknown. */
    fun seedGeneratedAt(context: Context): String = runCatching {
        JSONObject(context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }).optString("generatedAt", "")
    }.getOrDefault("")

    /**
     * Records the publish time of the data a refresh is about to install, BEFORE it installs it, so
     * [refreshedCopy] can tell it from the seed. "" for a source without an index.
     */
    fun noteRefreshed(context: Context, generatedAt: String) {
        PlatformDatabaseTransport.write(File(context.filesDir, REFRESHED_MARKER), generatedAt)
        useRefreshed = null
    }

    @Volatile
    private var useRefreshed: Boolean? = null

    /**
     * The downloaded copy of [fileName] in filesDir, when it is at least as new as this build's
     * bundled seed; null means read the seed. One rule for every platform database
     * ([KnownPlayers], [PlatformsDatabase], [BiosDatabase], EnginesDatabase, HardwareDatabase), so all
     * of them come from one snapshot.
     *
     * A downloaded copy used to win whatever its age, so an update that bundled newer data still
     * ran on the older download until the next refresh: the console on build 1386 had no
     * plain-path launch and no "needs All files access" row for NetherSX2, both of which its own
     * seed carried (the players rows gained `storagePathTemplate` that morning), and the platforms
     * database already carried a patch for one such case (ownership missing from older downloads).
     * Copies with no marker predate this rule and their age is unknown, so the seed is used until
     * the next refresh. Reads two small files: not for the main thread.
     */
    fun refreshedCopy(context: Context, fileName: String): File? {
        val file = File(context.filesDir, fileName).takeIf { it.isFile } ?: return null
        val use = useRefreshed ?: decide(context).also { useRefreshed = it }
        return file.takeIf { use }
    }

    private fun decide(context: Context): Boolean {
        val marker = File(context.filesDir, REFRESHED_MARKER).takeIf { it.isFile }
        val refreshedAt = marker?.let { runCatching { it.readText().trim() }.getOrDefault("") }
        return refreshedIsCurrent(refreshedAt, seedGeneratedAt(context))
    }

    /**
     * The rule, pure: [refreshedAt] is the marker (null: none, a copy from before the marker
     * existed; "": a source without an index), [seedAt] the seed's index time ("" unknown).
     * ISO-8601 UTC timestamps of the one shape the generator writes order as text.
     */
    internal fun refreshedIsCurrent(refreshedAt: String?, seedAt: String): Boolean = when {
        refreshedAt == null -> false
        refreshedAt.isEmpty() || seedAt.isEmpty() -> true
        else -> refreshedAt >= seedAt
    }
}
