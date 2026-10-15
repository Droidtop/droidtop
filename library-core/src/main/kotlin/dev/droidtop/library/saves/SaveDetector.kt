package dev.droidtop.library.saves

import android.content.Context
import android.os.Environment
import dev.droidtop.library.EngineHost
import dev.droidtop.library.EnginesDatabase
import dev.droidtop.library.GameEngine
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.StoreIdentity
import dev.droidtop.library.computers.ComputerLibrary
import dev.droidtop.library.engineFamily
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.EmulatorSetup
import dev.droidtop.library.consoles.KnownPlayers
import dev.droidtop.library.consoles.resolvePlayer
import dev.droidtop.library.scraper.PcGameIds
import dev.droidtop.library.stores.StoreSaves
import dev.droidtop.library.toLibraryEntryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Enginehost's save root for an engine family, where droidtop looks for the saves of a game Enginehost ran
 * (`<volume>/Enginehost/saves`, enginehost `SaveLocationStore.defaultRoot` + `saveRootFor`). The person may move it,
 * per family; Enginehost keeps that choice to itself, so this is the default until the Enginehost adapter plugin's
 * `saves.roots@1` answers with the real folders (Droidtop/tracker#546, design v3 D12).
 */
object EnginehostSaveRoots {
    @Suppress("DEPRECATION")
    fun defaultRoot(): File = File(File(Environment.getExternalStorageDirectory(), "Enginehost"), "saves")

    /** The root for [family], or null when Enginehost is not on this device and has never left one. */
    fun forFamily(context: Context, family: String?): File? {
        val root = defaultRoot()
        return root.takeIf { it.isDirectory || EngineHost.isInstalled(context) }
    }
}

/**
 * Finds a game's saves and says what it knows, in the honest states of [SaveStatus] (docs/SPEC.md 7o "Saves"). The
 * rules come from the engines database (engine games), the players database (emulator games) and the templates a
 * paired computer answered with (PC games); [SaveMatcher] settles which for a PC game, the match is kept in
 * `save_rules`, and the person's correction is never replaced.
 *
 * All of it is disk work done off the main thread by the caller; nothing runs while a list draws.
 */
object SaveDetector {
    /**
     * The report for [entry], or null for something that has no saves to speak of (an app, a streamed window, a game
     * that is on a computer and not here). Runs on IO.
     */
    suspend fun report(context: Context, entry: LibraryEntry): SaveReport? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (entry.kind in LibraryKinds.APPS || entry.kind == LibraryEntryKind.COMPUTER_GAME) return@withContext null
        val records = RoomSaveRecordStore(app)
        val saved = records.ruleOf(entry.id)
        if (saved != null && saved.how == SaveMatchHow.YOU && saved.kind == SaveRuleKind.NONE) {
            return@withContext SaveReport(SaveStatus.TurnedOff, null, SaveMatch(saved.name, SaveMatchHow.YOU).words())
        }
        val engine = GameEngine.entries.firstOrNull { it.toLibraryEntryKind() == entry.kind }
        // An engine with no rule of its own (Unity, Unreal) is a PC game like any other.
        val (report, kept) = when {
            entry.kind == LibraryEntryKind.CONSOLE_ROM -> romReport(app, entry)
            else -> (engine?.let { engineReport(app, entry, it) }) ?: pcReport(app, entry, records)
        }
        if (kept != null && (saved == null || saved.kind != kept.kind || saved.name != kept.name || saved.how != kept.how)) {
            runCatching { records.putRule(kept.copy(gameId = entry.id, checkedAt = System.currentTimeMillis())) }
        }
        report
    }

    /** The person says: do not look for this game's saves. */
    suspend fun turnOff(context: Context, entryId: String) = withContext(Dispatchers.IO) {
        RoomSaveRecordStore(context.applicationContext)
            .putRule(SaveRuleEntity(entryId, SaveRuleKind.NONE, "", SaveMatchHow.YOU, System.currentTimeMillis()))
    }

    /** The person takes the correction back: droidtop matches the game by itself again. */
    suspend fun matchAgain(context: Context, entryId: String) = withContext(Dispatchers.IO) {
        RoomSaveRecordStore(context.applicationContext).clearRule(entryId)
    }

    private class Kept(val kind: String, val name: String, val how: String) {
        fun copy(gameId: String, checkedAt: Long) = SaveRuleEntity(gameId, kind, name, how, checkedAt)
    }

    /** The report for an engine game, or null when the engine has no save rule (the caller then treats it as a PC game). */
    private fun engineReport(context: Context, entry: LibraryEntry, engine: GameEngine): Pair<SaveReport, Kept?>? {
        val def = EnginesDatabase.defFor(context, engine)
        val rule = def?.saves ?: return null
        val folder = PcRunnerOptions.gameFolderFor(entry)
        val matched = SaveMatch(def.id, SaveMatchHow.ENGINE)
        if (folder == null) {
            return SaveReport(SaveStatus.Unknown("the game's folder is not on this device", emptyList()), rule.source.take(120), null) to null
        }
        val hostFamily = EnginesDatabase.enginehostTargetFor(context, engine)?.engine
        val plan = SavePlans.engine(rule, folder, EnginehostSaveRoots.forFamily(context, hostFamily), wineRootsOf(context, entry.id)) { SaveFacts.read(it, folder) }
        val status = SaveSearch.run(plan)
        val family = entry.kind.engineFamily() ?: matched.name
        return SaveReport(status, plan.rule, "a $family game") to Kept(SaveRuleKind.ENGINE, matched.name, SaveMatchHow.ENGINE)
    }

    private suspend fun romReport(context: Context, entry: LibraryEntry): Pair<SaveReport, Kept?> {
        val systemId = entry.systemId ?: return SaveReport(SaveStatus.NoRule("this game has no system"), null, null) to null
        val system = ConsoleSystemsRepository.allSystems(context).firstOrNull { it.id == systemId }
            ?: return SaveReport(SaveStatus.NoRule("droidtop does not know the system $systemId"), null, null) to null
        val player = resolvePlayer(context, system, entry.altEmulator)
            ?: return SaveReport(SaveStatus.NoRule("no emulator is installed for ${system.displayName}"), null, null) to null
        val rule = KnownPlayers.savesFor(context, player.packageName)
            ?: return SaveReport(SaveStatus.NoRule("this emulator's saves aren't known yet. Nothing is lost: they stay where the emulator keeps them"), null, null) to null
        val rom = File(entry.id).takeIf { '/' in entry.id }
            ?: return SaveReport(SaveStatus.Unknown("the game's file is not on this device", emptyList()), rule.source.take(120), null) to null
        val configText = rule.configFile?.let { EmulatorSetup.read(it) }
        val plan = SavePlans.emulator(rule, systemId, rom, configText) { folder ->
            !folder.path.contains("/Android/data/") || runCatching { folder.listFiles() != null }.getOrDefault(false)
        }
        val status = SaveSearch.run(plan)
        return SaveReport(status, plan.rule, "played in ${player.name}") to Kept(SaveRuleKind.EMULATOR, player.packageName, SaveMatchHow.EMULATOR)
    }

    private suspend fun pcReport(context: Context, entry: LibraryEntry, records: SaveRecordStore): Pair<SaveReport, Kept?> {
        val specs = ComputerSpecs(context)
        val folder = PcRunnerOptions.gameFolderFor(entry)
        val own = PcGameIds.of(entry)
        val scraped = records.idsOf(entry.id)
        val identity = SaveIdentity(
            steamId = own.steamAppId?.toString() ?: entry.pcInfo?.externalIds?.get(GameIdKind.STEAM),
            gogId = own.gogId ?: entry.pcInfo?.externalIds?.get(GameIdKind.GOG),
            scraped = scraped,
            installFolder = folder?.name?.let { ComputerLibrary.titleKey(GameNaming.displayName(it)) },
            titleKey = StoreIdentity.titleKey(entry.title).takeIf { it.isNotEmpty() },
        )
        // The computer answered for this very game when it was synced: the strongest match there is.
        val match = if (specs.has(entry.id)) {
            SaveMatch(entry.id, if (identity.steamId != null) SaveMatchHow.STEAM else if (identity.gogId != null) SaveMatchHow.GOG else SaveMatchHow.TITLE)
        } else {
            SaveMatcher.match(identity, specs)
        }
        if (match == null) {
            return SaveReport(
                SaveStatus.NoRule("no save location is known for this game. A paired computer tells droidtop where a game keeps its saves the first time they sync it"),
                null,
                null,
            ) to null
        }
        val plan = SavePlans.templates("a paired computer's rule", specs.patterns(match.name), folder, wineRootsOf(context, entry.id))
        val status = SaveSearch.run(plan)
        return SaveReport(status, plan.rule, match.words()) to Kept(SaveRuleKind.COMPUTER, match.name, match.how)
    }

    /** The Windows token folders of the game's Wine prefix (the agent core's `prefix_roots`), or null when it has none. */
    private fun wineRootsOf(context: Context, entryId: String): Map<String, File>? =
        runCatching { StoreSaves.locator?.locate(context, entryId) }.getOrNull()?.let(SavePlans::prefixRoots)
}
