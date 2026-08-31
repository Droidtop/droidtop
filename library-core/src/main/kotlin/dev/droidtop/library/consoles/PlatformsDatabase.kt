package dev.droidtop.library.consoles

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * The data-driven platform definitions (docs/SPEC.md §7e2, extended per
 * direction 2026-08-31: launch resolution works FROM the platforms
 * database) -- generated from ES-DE's real es_systems.xml in the
 * droidtop-platforms repository (generator/from_esde_systems.py), bundled
 * as a seed asset, refreshable from GitHub with the same
 * validate-before-replace model as [KnownPlayers]/[BiosDatabase]. This
 * replaced the formerly compiled-in `ES_DE_CONSOLE_SYSTEMS` Kotlin list
 * as the seed for [ConsoleSystemsRepository]'s Room store (which remains
 * the runtime source of truth, because the user can edit platforms).
 *
 * [builtInsOrEmpty] exists for the few synchronous label lookups
 * (GamepadShell's group labels, the second-screen companion) that have no
 * suspend context -- it serves the last loaded cache, warmed at process
 * start by :app's settings-catalog init provider.
 */
object PlatformsDatabase {
    private const val DB_FILE_NAME = "platforms-database.json"
    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/bi0shacker001/droidtop-platforms/main/platforms-database.json"

    @Volatile
    private var cached: List<ConsoleSystemDef>? = null

    fun builtIns(context: Context): List<ConsoleSystemDef> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val updated = File(context.filesDir, DB_FILE_NAME)
                .takeIf { it.isFile }
                ?.let { runCatching { parse(it.readText()) }.getOrNull() }
            val loaded = updated
                ?: parse(context.assets.open(DB_FILE_NAME).bufferedReader().use { it.readText() })
            cached = loaded
            return loaded
        }
    }

    /** Cache-only, no-context variant for synchronous label lookups; empty until [builtIns] has run once this process. */
    fun builtInsOrEmpty(): List<ConsoleSystemDef> = cached ?: emptyList()

    fun displayNameOrNull(systemId: String): String? =
        builtInsOrEmpty().firstOrNull { it.id == systemId }?.displayName

    fun invalidate() {
        cached = null
    }

    private fun parse(text: String): List<ConsoleSystemDef> {
        val platforms = JSONObject(text).getJSONArray("platforms")
        val result = ArrayList<ConsoleSystemDef>(platforms.length())
        for (i in 0 until platforms.length()) {
            val platform = platforms.getJSONObject(i)
            val extensions = platform.optJSONArray("extensions")?.let { array ->
                buildSet { for (j in 0 until array.length()) add(array.getString(j).lowercase()) }
            } ?: emptySet()
            result += ConsoleSystemDef(
                id = platform.getString("id"),
                displayName = platform.optString("name", platform.getString("id")),
                extensions = extensions,
                retroArchCore = platform.optString("retroArchCore", "").ifEmpty { null },
            )
        }
        check(result.isNotEmpty()) { "Platforms database has no platforms" }
        return result
    }

    /** Same validate-before-replace atomic-write contract as [PlayersDatabaseUpdater]. Returns the platform count. */
    fun update(context: Context, url: String = DEFAULT_URL): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val text = try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode} from $url" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val count = parse(text).size

        val dest = File(context.filesDir, DB_FILE_NAME)
        val temp = File(context.filesDir, "$DB_FILE_NAME.downloading")
        temp.writeText(text)
        check(temp.renameTo(dest) || run { dest.delete(); temp.renameTo(dest) }) {
            "Couldn't move the downloaded platforms database into place"
        }
        invalidate()
        return count
    }
}
