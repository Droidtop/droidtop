package dev.droidtop.library

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Engine-game launch STRATEGY routing, data-driven from the
 * droidtop-platforms repository's engines-database.json (docs/SPEC.md
 * §7e2, extended per direction 2026-08-31: launch resolution works FROM
 * the platforms database) -- the per-engine strategy priority that
 * [GameLaunchStrategyResolver.resolve] previously hardcoded in its
 * append order. Availability stays code (is enginehost installed, does
 * the folder have a Windows exe / Linux build) -- the database only
 * declares which available strategy WINS, so a garbage download can
 * never make an unlaunchable strategy launch. Same bundled-seed +
 * GitHub-refresh + validate-before-replace model as [dev.droidtop.library.consoles.KnownPlayers].
 */
object EnginesDatabase {
    private const val DB_FILE_NAME = "engines-database.json"
    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/bi0shacker001/droidtop-platforms/main/engines-database.json"

    // GameEngine enum -> the database's engine ids (droidtop's own engine
    // vocabulary, shared with players-database.json's engine systemIds).
    private val ENGINE_IDS = mapOf(
        GameEngine.RENPY to "renpy",
        GameEngine.RPG_MAKER_MV to "rpgmaker-mv",
        GameEngine.RPG_MAKER_MZ to "rpgmaker-mz",
        GameEngine.RPG_MAKER_VX_ACE to "rpgmaker-vxace",
        GameEngine.KIRIKIRI to "kirikiri",
        GameEngine.AUGUST to "august",
        GameEngine.BURIKO to "buriko",
        GameEngine.CATSYSTEM2 to "catsystem2",
        GameEngine.CMVS to "cmvs",
        GameEngine.FLASH_AIR to "flash-air",
        GameEngine.GODOT to "godot",
        GameEngine.TWINE to "twine",
        GameEngine.UNREAL to "unreal",
        GameEngine.UNITY to "unity",
    )

    @Volatile
    private var cached: Map<String, List<GameLaunchStrategy>>? = null

    /** The database's declared priority order for [engine], or null when it has no entry. */
    fun priorityFor(context: Context, engine: GameEngine): List<GameLaunchStrategy>? =
        ENGINE_IDS[engine]?.let { all(context)[it] }

    fun all(context: Context): Map<String, List<GameLaunchStrategy>> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val updated = File(context.filesDir, DB_FILE_NAME)
                .takeIf { it.isFile }
                ?.let { runCatching { parse(it.readText()) }.getOrNull() }
            val loaded = updated
                ?: runCatching { parse(context.assets.open(DB_FILE_NAME).bufferedReader().use { it.readText() }) }
                    .getOrElse { emptyMap() }
            cached = loaded
            return loaded
        }
    }

    fun invalidate() {
        cached = null
    }

    private fun parse(text: String): Map<String, List<GameLaunchStrategy>> {
        val engines = JSONObject(text).getJSONArray("engines")
        val result = LinkedHashMap<String, List<GameLaunchStrategy>>()
        for (i in 0 until engines.length()) {
            val engine = engines.getJSONObject(i)
            val strategies = engine.getJSONArray("strategies")
            result[engine.getString("id")] = buildList {
                for (j in 0 until strategies.length()) {
                    // Unknown strategy names (a future database against an
                    // older app) are skipped, never fatal.
                    runCatching { GameLaunchStrategy.valueOf(strategies.getString(j)) }
                        .getOrNull()?.let { add(it) }
                }
            }
        }
        check(result.isNotEmpty()) { "Engines database has no engines" }
        return result
    }

    /** Same validate-before-replace atomic-write contract as [dev.droidtop.library.consoles.PlayersDatabaseUpdater]. Returns the engine count. */
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
            "Couldn't move the downloaded engines database into place"
        }
        invalidate()
        return count
    }
}
