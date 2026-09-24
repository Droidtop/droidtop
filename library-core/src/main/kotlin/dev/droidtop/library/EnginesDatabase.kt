package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.consoles.PlatformDatabaseSource
import dev.droidtop.library.consoles.PlatformDatabaseTransport
import java.io.File

/**
 * The engine REGISTRY, data-driven from the droidtop-platforms
 * repository's engines-database.json (docs/SPEC.md §7e2b; extended to
 * v4 per direction 2026-08-31: detection rules, launch-strategy
 * priority, and the enginehost family/context vocabulary ALL live in
 * the database, so new engines and contexts ship as a data update, not
 * an app rebuild). Availability stays code (is enginehost installed,
 * does the folder hold a Windows exe) -- the database declares what
 * exists and which available strategy WINS, so a garbage download can
 * never make an unlaunchable strategy launch.
 *
 * Same bundled-seed + GitHub-refresh + validate-before-replace model as
 * [dev.droidtop.library.consoles.KnownPlayers]. One extra validation
 * for v4: a database that parses but carries no detection rules at all
 * is a legacy v3 file, and loading it would silently turn engine
 * scanning off -- rejected, both at [update] time and when reading a
 * stale filesDir copy left by an older app version.
 */
object EnginesDatabase {
    private const val DB_FILE_NAME = "engines-database.json"

    @Volatile
    private var cached: List<EngineDef>? = null

    /** Every registry row, database file order (which IS detection priority). */
    fun defs(context: Context): List<EngineDef> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val updated = File(context.filesDir, DB_FILE_NAME)
                .takeIf { it.isFile }
                ?.let { file -> runCatching { EngineRegistryParser.parse(file.readText()) }.getOrNull() }
                ?.takeIf { defs -> defs.any { it.detect.isNotEmpty() } }
            val loaded = updated
                ?: runCatching {
                    EngineRegistryParser.parse(
                        context.assets.open(DB_FILE_NAME).bufferedReader().use { it.readText() },
                    )
                }.getOrElse { emptyList() }
            cached = loaded
            return loaded
        }
    }

    fun defFor(context: Context, engine: GameEngine): EngineDef? =
        defs(context).firstOrNull { it.engine == engine }

    /** The database's declared strategy priority for [engine], or null when it has no entry. */
    fun priorityFor(context: Context, engine: GameEngine): List<GameLaunchStrategy>? =
        defFor(context, engine)?.strategies?.takeIf { it.isNotEmpty() }

    /** The enginehost family/context mapping for [engine], or null when enginehost doesn't cover it. */
    fun enginehostTargetFor(context: Context, engine: GameEngine): EnginehostTarget? =
        defFor(context, engine)?.enginehost

    fun invalidate() {
        cached = null
    }

    /** Same validate-before-replace atomic-write contract as [dev.droidtop.library.consoles.PlayersDatabaseUpdater]. Returns the engine count. */
    fun update(context: Context, url: String = PlatformDatabaseSource.urlFor(context, DB_FILE_NAME)): Int =
        install(context, PlatformDatabaseTransport.get(url))

    /**
     * Validates [text] as a real registry and, only then, replaces the
     * current copy with it. Public because the index-driven refresh
     * ([dev.droidtop.library.consoles.PlatformDatabaseIndex]) composes this
     * file out of per-engine files rather than downloading it whole -- the
     * validation and the atomic replace have to be the same either way.
     */
    fun install(context: Context, text: String): Int {
        val count = validate(text)
        PlatformDatabaseTransport.replace(context, DB_FILE_NAME, text)
        invalidate()
        return count
    }

    /** The check [install] makes, without writing anything. Returns the engine count. */
    fun validate(text: String): Int {
        val parsed = EngineRegistryParser.parse(text)
        check(parsed.isNotEmpty()) { "Engines database has no engines" }
        check(parsed.any { it.detect.isNotEmpty() }) {
            "Engines database carries no detection rules (legacy v3 file?) -- refusing to replace the seed"
        }
        return parsed.size
    }
}
