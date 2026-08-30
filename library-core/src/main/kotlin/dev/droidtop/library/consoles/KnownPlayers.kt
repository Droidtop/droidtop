package dev.droidtop.library.consoles

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Real standalone-emulator [Player.AmStart] presets, one per (system, real
 * installed emulator app) combination — loaded from the DATA-DRIVEN player
 * database (`players-database.json`) instead of the generated Kotlin this
 * object used to hardcode (per direction, 2026-08-30: player/platform
 * definitions live in separately-updatable JSON, not code, so the database
 * can grow without an app release).
 *
 * Two sources, checked in order:
 *  1. `filesDir/players-database.json` — a database updated from the
 *     droidtop platform-db repository on GitHub (user-driven refresh; see
 *     [PlayersDatabaseUpdater]). A copy that fails to parse is ignored
 *     (never lets a bad download brick player resolution).
 *  2. The bundled asset seed — the same 117 real presets the hardcoded
 *     version carried, originally generated from Daijishō's own public
 *     wiki (github.com/TapiocaFox/Daijishou/wiki/Start-Arguments); the
 *     platform-db repo's generator regenerates them from Daijishō and
 *     ES-DE mobile's own `es_systems.xml`/`es_find_rules.xml` (see
 *     docs/SPEC.md §7h).
 *
 * [KnownPlayerPreset.pkg] is the real launcher-resolvable package name,
 * used by [ConsoleRomProvider] to only ever offer/default to a player whose
 * app is actually installed (checked via PackageManager) — never claims an
 * emulator is available just because a preset exists in the database.
 */
data class KnownPlayerPreset(
    val id: String,
    val systemId: String,
    val label: String,
    val pkg: String,
    val player: Player.AmStart,
)

object KnownPlayers {
    private const val DB_FILE_NAME = "players-database.json"

    @Volatile
    private var cached: List<KnownPlayerPreset>? = null

    /** Every real preset registered for [systemId], regardless of whether the emulator is installed. */
    fun forSystem(context: Context, systemId: String): List<KnownPlayerPreset> =
        all(context).filter { it.systemId == systemId }

    fun all(context: Context): List<KnownPlayerPreset> {
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

    /** Drops the parse cache — called after [PlayersDatabaseUpdater] writes a fresh database. */
    fun invalidate() {
        cached = null
    }

    private fun parse(text: String): List<KnownPlayerPreset> {
        val root = JSONObject(text)
        val players = root.getJSONArray("players")
        return (0 until players.length()).map { i ->
            val p = players.getJSONObject(i)
            val id = p.getString("id")
            val pkg = p.getString("pkg")
            KnownPlayerPreset(
                id = id,
                systemId = p.getString("systemId"),
                label = p.getString("label"),
                pkg = pkg,
                player = Player.AmStart(
                    id = id,
                    name = p.getString("label"),
                    argumentsTemplate = p.getString("argumentsTemplate"),
                    killPackageProcesses = p.optBoolean("killPackageProcesses", false),
                    packageName = pkg,
                ),
            )
        }
    }
}
