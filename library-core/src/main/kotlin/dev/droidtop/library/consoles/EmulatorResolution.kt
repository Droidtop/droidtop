package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the emulator that will run a game was chosen. The order here is
 * the order of precedence: a game's own setting beats its system's, which
 * beats the person's global default, which beats droidtop's own fallback.
 */
enum class EmulatorSource(val label: String) {
    GAME("set for this game"),
    SYSTEM("set for this system"),
    GLOBAL("your default emulator"),
    AUTOMATIC("the first installed emulator that can run it"),
}

/** The emulator that will run, and which level of settings it came from. */
data class ResolvedEmulator(val player: Player.AmStart, val source: EmulatorSource)

/**
 * THE one function that decides which emulator runs a game, used by the
 * launcher ([resolveEmulator], [resolvePlayer]) and by every screen that
 * shows or edits the choice, so what the screens say is what a launch
 * does (docs/SPEC.md, "Launch resolution: keep the default, expose it").
 *
 * [candidates] are the installed players for the game's system, already in
 * droidtop's preference order. A choice naming something that is not in
 * [candidates] (an emulator uninstalled since it was chosen) is skipped
 * and the next level answers, so a stale choice never fails a launch.
 */
object EmulatorResolution {
    /**
     * [gameChoice] is the game's own setting, matched against a player's id
     * and its label, because ES-DE's `altemulator` holds the name a person
     * typed ("RetroArch"), not an internal id. [systemChoiceId] is the
     * system's stored player id. [globalPackage] is the global default
     * emulator app: the first candidate belonging to it wins, so one
     * choice ("RetroArch") applies to every system that app can run.
     */
    fun resolve(
        candidates: List<Player.AmStart>,
        gameChoice: String?,
        systemChoiceId: String?,
        globalPackage: String?,
    ): ResolvedEmulator? {
        matchGameChoice(candidates, gameChoice)?.let { return ResolvedEmulator(it, EmulatorSource.GAME) }
        return resolveWithoutGame(candidates, systemChoiceId, globalPackage)
    }

    /** The candidate a game's own setting names, or null when it names none that is installed. */
    fun matchGameChoice(candidates: List<Player.AmStart>, gameChoice: String?): Player.AmStart? {
        val game = gameChoice?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return candidates.firstOrNull { it.id.equals(game, ignoreCase = true) || it.name.equals(game, ignoreCase = true) }
    }

    /** What applies to a system's games that have no setting of their own. */
    fun resolveWithoutGame(
        candidates: List<Player.AmStart>,
        systemChoiceId: String?,
        globalPackage: String?,
    ): ResolvedEmulator? {
        if (systemChoiceId != null) {
            candidates.firstOrNull { it.id == systemChoiceId }
                ?.let { return ResolvedEmulator(it, EmulatorSource.SYSTEM) }
        }
        if (globalPackage != null) {
            candidates.firstOrNull { it.packageName == globalPackage }
                ?.let { return ResolvedEmulator(it, EmulatorSource.GLOBAL) }
        }
        return candidates.firstOrNull()?.let { ResolvedEmulator(it, EmulatorSource.AUTOMATIC) }
    }
}

/**
 * The person's global default emulator: an emulator APP (its package), so
 * one choice covers every system that app can run. Same prefs file and
 * keyed-string store as [PlayerOverridePrefs], the system-level twin.
 */
object EmulatorDefaults {
    private const val KEY = "package"
    private val store = { context: Context -> PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).keyedStrings("droidtop_emulator_default_") }

    fun globalPackage(context: Context): String? = store(context).get(KEY)

    fun setGlobalPackage(context: Context, packageName: String?) = store(context).set(KEY, packageName)
}

/**
 * [EmulatorResolution.resolve] with the stored choices read from prefs.
 * The PackageManager walk inside [availablePlayers] makes this a call for
 * a background thread, never for drawing a list row.
 */
fun resolveEmulator(context: Context, system: ConsoleSystemDef, altEmulator: String? = null): ResolvedEmulator? =
    EmulatorResolution.resolve(
        availablePlayers(context, system),
        altEmulator,
        PlayerOverridePrefs.get(context, system.id),
        EmulatorDefaults.globalPackage(context),
    )

/**
 * What a screen needs to show and edit one system's emulator choice:
 * every installed candidate and what applies when a game sets nothing.
 */
class SystemEmulators(
    val system: ConsoleSystemDef,
    val candidates: List<Player.AmStart>,
    val withoutGameChoice: ResolvedEmulator?,
)

/** Loads [SystemEmulators] for [systemId] off the main thread; null when the system is unknown. */
suspend fun loadSystemEmulators(context: Context, systemId: String): SystemEmulators? = withContext(Dispatchers.IO) {
    val system = ConsoleSystemsRepository.allSystems(context).firstOrNull { it.id == systemId } ?: return@withContext null
    val candidates = availablePlayers(context, system)
    SystemEmulators(
        system,
        candidates,
        EmulatorResolution.resolveWithoutGame(
            candidates,
            PlayerOverridePrefs.get(context, system.id),
            EmulatorDefaults.globalPackage(context),
        ),
    )
}

/**
 * One emulator app found for some systems, for the global Emulators page:
 * the app, whether it is on this device, and what it can run.
 */
data class EmulatorApp(val packageName: String, val installed: Boolean, val systemIds: List<String>)

/**
 * Groups (systemId, player) pairs by emulator app. Pure so it can be
 * tested; [detectEmulatorApps] feeds it the real presets.
 */
internal fun groupEmulatorApps(pairs: List<Pair<String, Player.AmStart>>, installed: (String) -> Boolean): List<EmulatorApp> =
    pairs.groupBy({ it.second.packageName }, { it.first })
        .map { (pkg, systems) -> EmulatorApp(pkg, installed(pkg), systems.distinct()) }

/**
 * Every emulator app droidtop knows (the players database, the person's
 * custom players, RetroArch's known package ids) with whether it is
 * installed. Reads the PackageManager once per package: background only.
 */
fun detectEmulatorApps(context: Context, systems: List<ConsoleSystemDef>): List<EmulatorApp> {
    val pairs = buildList {
        KnownPlayers.all(context).forEach { add(it.systemId to it.player) }
        systems.forEach { system ->
            CustomPlayerPrefs.getForSystem(context, system.id).forEach { add(system.id to it) }
            DefaultPlayers.retroArch(context, system)?.let { add(system.id to it) }
        }
    }
    val cache = HashMap<String, Boolean>()
    return groupEmulatorApps(pairs) { pkg -> cache.getOrPut(pkg) { isPackageInstalled(context, pkg) } }
}
