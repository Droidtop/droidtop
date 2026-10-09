package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How ONE game starts under Wine, where that differs from what droidtop
 * works out on its own: which program in the game's folder, with which
 * arguments, from which folder (docs/SPEC.md 7i, "Overrides").
 *
 * Per game, never on the prefix: droidtop's prefix is shared by every
 * Windows game without one of its own (§5b), and one game's program and
 * arguments are not another's. Paths are relative to the game's folder,
 * so a folder that moves with its game keeps them.
 *
 * Three writers: the Lutris importer (§7e3), whose [source] says so on the
 * game's own screen, the person's program choice ([WindowsPrograms]), and the
 * person's own launch options and environment ([GameProperties]). The first
 * two are the program half ([executable], [arguments], [workingDir],
 * [source]); clearing it returns the game to detection and leaves the
 * person's own half ([launchOptions], [environment]) as it was.
 */
@Serializable
data class WineGameSettings(
    /** The program to run, relative to the game's folder, `/`-separated. */
    val executable: String? = null,
    val arguments: List<String> = emptyList(),
    /** The folder to start in, relative to the game's folder; null is the game's folder. */
    val workingDir: String? = null,
    /** Where these came from, in the person's words ("Lutris: GOG installer"). */
    val source: String? = null,
    /**
     * What the person typed after the program, any program ([GameLaunchOptions.tokenize]),
     * added after [arguments] and after the store's own. Null when none.
     */
    val launchOptions: String? = null,
    /** Variables the person set for this game ([GameLaunchOptions.parseEnvironment]); applied to the one launch, never to the prefix. */
    val environment: Map<String, String> = emptyMap(),
) {
    /** Nothing set: the game is on detection and has no options of its own. */
    val isEmpty: Boolean
        get() = executable == null && arguments.isEmpty() && workingDir == null && source == null &&
            launchOptions.isNullOrBlank() && environment.isEmpty()
}

/** [WineGameSettings] by [LibraryEntry.id], the same keying as [LaunchStrategyOverridePrefs]. */
object WineGameSettingsPrefs {
    private const val KEY_PREFIX = "droidtop_wine_game_settings_"
    private val json = Json { ignoreUnknownKeys = true }

    fun get(context: Context, entryId: String): WineGameSettings? =
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + entryId, null)
            ?.let { runCatching { json.decodeFromString(WineGameSettings.serializer(), it) }.getOrNull() }

    /** Every game's settings by entry id, in one preferences read: for a list's lookup, never one per game. */
    fun all(context: Context): Map<String, WineGameSettings> =
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
            val settings = (value as? String)?.let { runCatching { json.decodeFromString(WineGameSettings.serializer(), it) }.getOrNull() }
            settings?.let { key.removePrefix(KEY_PREFIX) to it }
        }.toMap()

    fun set(context: Context, entryId: String, settings: WineGameSettings?) {
        val prefs = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
        if (settings == null || settings.isEmpty) {
            prefs.remove(KEY_PREFIX + entryId)
        } else {
            prefs.putString(KEY_PREFIX + entryId, json.encodeToString(WineGameSettings.serializer(), settings))
        }
        prefs.apply()
    }
}

/**
 * Replaces the program half of [entryId]'s settings with [program] (null:
 * back to detection) and keeps the person's own launch options and
 * environment, which belong to the game and not to the program. Both
 * program writers use this.
 */
fun WineGameSettingsPrefs.setProgram(context: Context, entryId: String, program: WineGameSettings?) {
    val old = get(context, entryId)
    set(
        context,
        entryId,
        (program ?: WineGameSettings()).copy(launchOptions = old?.launchOptions, environment = old?.environment.orEmpty()),
    )
}

/** Changes the settings of [entryId] through [change], starting from none; an empty result removes them. */
fun WineGameSettingsPrefs.edit(context: Context, entryId: String, change: (WineGameSettings) -> WineGameSettings) {
    set(context, entryId, change(get(context, entryId) ?: WineGameSettings()))
}

/**
 * One game's own Wine choices over the shared prefix (docs/SPEC.md 5a):
 * graphics driver and its build, Direct3D and the DXVK/VKD3D versions, the
 * emulator and its FEXCore/Box64 version, by name, only those that differ
 * from the shared settings. Applied at launch; the shared prefix itself is
 * never changed by them. Kept apart from [WineGameSettings] so clearing a
 * game's program does not clear its graphics. By [LibraryEntry.id].
 */
object WineGameOptionsPrefs {
    private const val KEY_PREFIX = "droidtop_wine_game_options_"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun get(context: Context, entryId: String): Map<String, String> =
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + entryId, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    /** Stores [choices]; an empty map removes the game's entry. */
    fun set(context: Context, entryId: String, choices: Map<String, String>) {
        val prefs = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
        if (choices.isEmpty()) prefs.remove(KEY_PREFIX + entryId) else prefs.putString(KEY_PREFIX + entryId, json.encodeToString(serializer, choices))
        prefs.apply()
    }
}

/**
 * The settings screen a game's "Wine and graphics" row opens: Wine build,
 * x86 emulation, graphics driver and Direct3D for the prefix it runs in
 * (docs/SPEC.md 5a). Registered by `:app` (WineOptionsCatalog) and opened
 * by id from the game's page, deep-linked with [argument].
 */
object WineSettingsScreen {
    const val ID = "windows_game_wine"
    private const val SEPARATOR = "\n"

    /** One game the screen is for: its library id, its title, and its folder when it has one (for the Program row). */
    data class Target(val entryId: String, val title: String, val gameRoot: String?)

    /** The deep-link argument for one game: its library id, its title and its folder. */
    fun argument(entryId: String, title: String, gameRoot: String? = null): String =
        listOfNotNull(entryId, title, gameRoot).joinToString(SEPARATOR)

    /** [argument] read back. */
    fun parse(argument: String): Target {
        val parts = argument.split(SEPARATOR)
        return Target(parts[0], parts.getOrNull(1) ?: parts[0], parts.getOrNull(2)?.takeIf { it.isNotBlank() })
    }
}

/** What a Windows launch of one game runs: the program, where it starts, and its arguments. */
data class WindowsLaunch(val executable: File, val workingDir: File, val arguments: List<String>)

/**
 * The one answer to "what does this game run under Wine", for every
 * Windows launch path (the engine detector's and the PC provider's).
 *
 * The game's own [WineGameSettings] win when they still name something
 * inside [gameRoot] -- re-checked here on every launch, canonically, so a
 * setting can never lead out of the game's folder even if the folder
 * changed after it was saved. Otherwise it is what
 * [GameExecutableResolver.windowsExecutable] detects, as before.
 * Disk work: never on the main thread.
 */
object WindowsLaunchResolver {
    fun resolve(settings: WineGameSettings?, gameRoot: File): WindowsLaunch? {
        val root = runCatching { gameRoot.canonicalFile }.getOrNull() ?: return null
        val chosen = settings?.executable?.let { inside(root, it) }?.takeIf { it.isFile && it.extension.equals("exe", ignoreCase = true) }
        // Arguments and a start folder belong to the program they were
        // saved with; a detected program starts plain, as it always did.
        if (settings == null || chosen == null) {
            val detected = GameExecutableResolver.windowsExecutable(gameRoot) ?: return null
            return withUserOptions(WindowsLaunch(detected, gameRoot, emptyList()), settings)
        }
        val workingDir = settings.workingDir?.let { inside(root, it) }?.takeIf { it.isDirectory } ?: gameRoot
        return withUserOptions(WindowsLaunch(chosen, workingDir, settings.arguments), settings)
    }

    /**
     * [launch] with the person's own launch options added after its
     * arguments, whatever program it is ([WineGameSettings.launchOptions]).
     * The one place they are added, for the detected program, the chosen one
     * and a store's own.
     */
    fun withUserOptions(launch: WindowsLaunch, settings: WineGameSettings?): WindowsLaunch {
        val own = settings?.launchOptions?.let(GameLaunchOptions::tokenize).orEmpty()
        return if (own.isEmpty()) launch else launch.copy(arguments = launch.arguments + own)
    }

    /**
     * What a PC game of [entryId] in [gameRoot] runs: the program the person
     * picked, else the one the game's store itself starts (a GOG play task,
     * docs/SPEC.md 7g "Stores"), else the one droidtop finds in the folder,
     * with the person's own launch options after the arguments. The one
     * answer for the launch and for the Show command rows. Disk work, done
     * here off the main thread.
     */
    suspend fun forEntry(context: Context, entryId: String, gameRoot: File): WindowsLaunch? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val picked = WineGameSettingsPrefs.get(context, entryId)
            val storeLaunch = if (picked?.executable == null) {
                dev.droidtop.library.stores.StoreLibraries.forKey(entryId)
                    ?.let { store -> runCatching { store.launch(context, entryId.substringAfter(':')) }.getOrNull() }
                    ?.takeIf { it.executable.isFile }
            } else {
                null
            }
            storeLaunch?.let { withUserOptions(WindowsLaunch(it.executable, it.workingDir, it.arguments), picked) }
                ?: resolve(picked, gameRoot)
        }

    fun resolve(context: Context, entryId: String, gameRoot: File): WindowsLaunch? =
        resolve(WineGameSettingsPrefs.get(context, entryId), gameRoot)

    private fun inside(root: File, relative: String): File? {
        val file = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }
}
