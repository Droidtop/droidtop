package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.EmulatorDefaults
import dev.droidtop.library.consoles.EmulatorResolution
import dev.droidtop.library.consoles.EmulatorSource
import dev.droidtop.library.consoles.PlayerOverridePrefs
import dev.droidtop.library.consoles.ResolvedEmulator
import dev.droidtop.library.consoles.availablePlayers
import dev.droidtop.library.consoles.libretroCoreId
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * `library.read@1` op `systems` (docs/plugin-api.md 3 A1, docs/SPEC.md 12a): the systems the person has games for and,
 * per system, the emulator that launches it. This is the whole of what a plugin learns about the library through this
 * call: no titles, no paths, no entry ids. Lives in `library-core` because the library and the emulator resolution do;
 * the broker in `plugin-host` reaches it through `PluginBrokers.librarySystemsProvider`.
 *
 * It reads the in-memory index the shells already show ([Library.backgroundScanState]), so a call walks no folder. The
 * emulator is the one a launch would use ([EmulatorResolution.resolveWithoutGame]), so the answer and a launch agree.
 */
object PluginLibraryRead {
    /** How long a call waits for the first publication of the index in a process nobody has opened the library in yet. */
    const val FIRST_SCAN_WAIT_MS = 5_000L

    /** One system as a plugin sees it. [player] is null when nothing installed can run the system. */
    data class SystemRow(
        val id: String,
        val name: String,
        val games: Int,
        val player: ResolvedEmulator?,
        val core: String,
    )

    /**
     * The systems that hold at least one game that is there ([LibraryEntry.missing] is false), by name. [defs] names a
     * system and gives its configured core; a system the definitions do not know keeps its id as its name. [resolve]
     * picks the emulator for a system and may touch the PackageManager, so the caller runs this off the main thread.
     */
    fun systems(
        entries: List<LibraryEntry>,
        defs: Map<String, ConsoleSystemDef>,
        resolve: (ConsoleSystemDef) -> ResolvedEmulator?,
    ): List<SystemRow> = entries
        .asSequence()
        .filter { it.kind == LibraryEntryKind.CONSOLE_ROM && !it.missing }
        .mapNotNull { it.systemId }
        .groupingBy { it }
        .eachCount()
        .map { (id, games) ->
            val def = defs[id]
            val player = def?.let(resolve)
            val core = if (def != null && player != null) libretroCoreId(player.player, def.retroArchCore).orEmpty() else ""
            SystemRow(id, def?.displayName ?: id, games, player, core)
        }
        .sortedBy { it.name.lowercase() }

    /**
     * The reply data: `{ready, systems: [{id, name, games, choice, playerId, playerName, playerPackage, core}]}`.
     * `choice` says where the emulator came from: `system` (the person chose it for the system), `global` (their default
     * emulator), `automatic` (the first installed one), or `none` (nothing installed runs it; the player fields are
     * empty strings, as in the `default_player_changed` event, so a plugin reads both the same way).
     */
    fun toJson(rows: List<SystemRow>, ready: Boolean = true): JSONObject {
        val array = JSONArray()
        for (row in rows) {
            array.put(
                JSONObject()
                    .put("id", row.id)
                    .put("name", row.name)
                    .put("games", row.games)
                    .put("choice", row.player?.source?.let(::choiceId) ?: "none")
                    .put("playerId", row.player?.player?.id.orEmpty())
                    .put("playerName", row.player?.player?.name.orEmpty())
                    .put("playerPackage", row.player?.player?.packageName.orEmpty())
                    .put("core", row.core),
            )
        }
        return JSONObject().put("ready", ready).put("systems", array)
    }

    /** This resolution is for a system, not one game, so `GAME` cannot occur; it reads as the person's own choice if it ever did. */
    private fun choiceId(source: EmulatorSource): String = when (source) {
        EmulatorSource.GAME, EmulatorSource.SYSTEM -> "system"
        EmulatorSource.GLOBAL -> "global"
        EmulatorSource.AUTOMATIC -> "automatic"
    }

    /**
     * The production answer. Blocks its caller (a binder thread, never the main thread). When no surface has published
     * the library in this process yet it starts the ordinary first load (the index, as any shell does) and waits up to
     * [FIRST_SCAN_WAIT_MS]; past that it says `ready: false` with no systems, and the plugin asks again later.
     */
    suspend fun snapshot(context: Context, library: Library): JSONObject {
        val state = library.backgroundScanState(LibraryKinds.GAMES)
        val entries = state.value ?: run {
            library.scanInBackground(LibraryKinds.GAMES)
            withTimeoutOrNull(FIRST_SCAN_WAIT_MS) { state.filterNotNull().first() }
        } ?: return toJson(emptyList(), ready = false)
        val defs = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val globalPackage = EmulatorDefaults.globalPackage(context)
        val rows = systems(entries, defs) { system ->
            EmulatorResolution.resolveWithoutGame(availablePlayers(context, system), PlayerOverridePrefs.get(context, system.id), globalPackage)
        }
        return toJson(rows)
    }
}
