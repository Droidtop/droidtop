package dev.droidtop.runtime.windows

import android.content.Context
import dev.droidtop.runtime.windows.R
import dev.droidtop.runtime.windows.utils.ComponentCatalog
import dev.droidtop.runtime.windows.utils.ContainerUtils
import dev.droidtop.runtime.windows.utils.LsfgVkManager
import dev.droidtop.runtime.windows.utils.ManifestComponentHelper
import dev.droidtop.runtime.windows.utils.ManifestContentTypes
import dev.droidtop.runtime.windows.utils.ManifestEntry
import dev.droidtop.runtime.windows.utils.SteamworksShim
import dev.droidtop.runtime.windows.utils.X86_64GuestLibs
import dev.droidtop.runtime.windows.utils.X86_64Graphics
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.DefaultVersion
import dev.droidtop.library.WineGameOptionsPrefs
import com.winlator.core.KeyValueSet
import com.winlator.core.StringUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One choice of a [WineOptionRow], as the settings row shows it. */
data class WineOptionChoice(val value: String, val label: String)

/**
 * One Wine option of one prefix. [choices] empty means the Wine build and the
 * CPU leave no choice; [summary] then says why [current] is what it is.
 */
data class WineOptionRow(
    val id: String,
    val title: String,
    val summary: String,
    val current: String,
    val choices: List<WineOptionChoice>,
    /** A game's own choice over the shared setting, rather than the shared value showing through. */
    val ownChoice: Boolean = false,
    /** The value is typed, not picked ([current] is the text; [choices] are empty). */
    val text: Boolean = false,
)

/**
 * The Wine options a game (or, with no game, every game) runs with.
 * [ownPrefix]: the game has a prefix of its own (it chose another Wine
 * build), and the rows edit that prefix. Otherwise a game's rows are its own
 * choices over the shared prefix; [ownChoices] counts them. [missing] names
 * what the settings need that is not on the device yet.
 */
data class WineOptionsState(
    val prefixName: String,
    val ownPrefix: Boolean,
    val ownChoices: Int,
    val rows: List<WineOptionRow>,
    val missing: List<String>,
    /** False before Set up Windows games: the rows then show the device's defaults and the choices made for the setup to use. */
    val setUp: Boolean = true,
)

/**
 * Wine build, x86 emulation, graphics driver and Direct3D translation
 * (docs/SPEC.md 5a): the rows Settings > Windows games shows for the shared
 * environment and a game's Wine settings show for that game.
 *
 * Where a value lives (owner, 2026-10-02): the shared environment's values
 * are fields of its gamenative `Container`, written through
 * [ContainerUtils.applyToContainer], the path gamenative's own configuration
 * dialog saves through. A game's own choices of everything but the Wine
 * build are kept per game ([WineGameOptionsPrefs]) and laid over the shared
 * prefix at launch ([launchOverrides], the fork's
 * `Container.setLaunchOverrides`), so they cost no prefix. Choosing another
 * Wine build gives the game a prefix of its own, made with that build and
 * the game's choices; from then on its rows edit that prefix like the shared
 * one. What is offered is not listed here: the runtime's bundled versions,
 * what is installed (a Wine build a person added among it, [WineBuilds]), and
 * droidtop's component catalog, its enabled sources only
 * ([ManifestComponentHelper.loadComponentAvailability]); which
 * of those apply to which Wine build and CPU is [WineOptionPlan]. Anything
 * chosen that is not on the device yet is fetched by [WineComponents] before
 * the next launch, or at once from the Download row.
 *
 * Plain values in and out, so `:app` needs none of gamenative's types.
 */
object WineOptions {
    const val WINE = "wine_build"
    const val EMULATOR = "wine_emulator"
    const val FEXCORE = "wine_fexcore"
    const val BOX64 = "wine_box64"
    const val DRIVER = "wine_driver"
    const val DRIVER_VERSION = "wine_driver_version"
    const val DXWRAPPER = "wine_dxwrapper"
    const val DXVK = "wine_dxvk"
    const val VKD3D = "wine_vkd3d"

    /** The frame generation row of a game: not a prefix setting, so its own key and its own place among a game's choices. */
    const val LSFG = "wine_lsfg"
    private const val LSFG_KEY = "lsfg"
    private const val LSFG_OFF = "off"

    /**
     * Whether a game starts through the Steamworks shim in its prefix (docs/SPEC.md 5b), and as which Steam
     * app: a game's own choices. Unchosen, a game Steam owns is on and any other is off; the app id is
     * the detected one unless the person typed one.
     */
    const val STEAMWORKS = "wine_steamworks"
    const val STEAMWORKS_APPID = "wine_steamworks_appid"
    private const val STEAMWORKS_KEY = "steamworks"
    private const val STEAMWORKS_APPID_KEY = "steamworks_appid"
    private const val STEAMWORKS_ON = "on"
    private const val STEAMWORKS_OFF = "off"

    /** A game's choices that are not prefix settings: kept when its Wine choices change or are dropped. */
    private val OWN_KEYS = setOf(LSFG_KEY, STEAMWORKS_KEY, STEAMWORKS_APPID_KEY)

    /**
     * Where the choices made before Set up Windows games are kept (in [WineGameOptionsPrefs], under a name no
     * library entry has): the [WineOptionPlan.GAME_KEYS] names that differ from the device's defaults, and
     * "wine" for the Wine build. Setup creates the shared environment from them ([withSetupChoices]).
     */
    private const val SETUP_ENTRY = "droidtop:setup-defaults"
    private const val WINE_KEY = "wine"

    private const val NOT_HERE = "downloads when used"

    // Settings rows choose synchronously on the main thread; the write is
    // disk work, so it runs here, and the next read waits for it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastWrite: Job? = null

    /** Null when there is no Windows environment yet (the setup step, not an error). */
    suspend fun state(context: Context, entryId: String?, gameRoot: String? = null): WineOptionsState = withContext(Dispatchers.IO) {
        lastWrite?.join()
        WindowsBackbone.awaitReady(context)
        val container = PcContainers.forGame(context, entryId) ?: return@withContext stateBeforeSetup(context, entryId, gameRoot)
        val own = entryId != null && PcContainers.isOwnPrefix(entryId, container)
        val data = ContainerUtils.toContainerData(container)
        val shared = read(data)
        val choices = if (entryId != null && !own) gameChoices(context, entryId) else emptyMap()
        val settings = WineOptionPlan.merge(shared, choices)
        // What a launch of this game would need, its own choices included.
        container.setLaunchOverrides(overrides(data, settings))
        WineOptionsState(
            prefixName = container.name?.takeIf { it.isNotBlank() } ?: container.id,
            ownPrefix = own,
            ownChoices = choices.size,
            rows = rows(context, settings, Lists.load(context), choices.keys) + gameRows(context, entryId, gameRoot),
            missing = runCatching { WineComponents.missing(context, container) }.getOrDefault(emptyList()),
        )
    }

    /** A game's rows that are not prefix settings: frame generation and Steamworks. Disk work. */
    private suspend fun gameRows(context: Context, entryId: String?, gameRoot: String?): List<WineOptionRow> =
        if (entryId == null) emptyList() else listOf(lsfgRow(context, entryId)) + steamworksRows(context, entryId, gameRoot?.let(::File))

    /**
     * The rows before Set up Windows games has made the environment: the device's own defaults (what setup
     * will create) with the choices made so far over them, so the Wine build, emulation and graphics can be
     * chosen before the first download. A game's own choices work as they do after setup, except the Wine
     * build, which needs a prefix of its own to exist: that row only says so.
     */
    private suspend fun stateBeforeSetup(context: Context, entryId: String?, gameRoot: String?): WineOptionsState {
        val data = ContainerUtils.deviceDefaultContainerData(context)
        val shared = withSetupChoices(read(data), setupChoices(context))
        val choices = if (entryId != null) gameChoices(context, entryId) else emptyMap()
        val settings = WineOptionPlan.merge(shared, choices)
        val rows = rows(context, settings, Lists.load(context), choices.keys).map { row ->
            if (entryId != null && row.id == WINE) {
                row.copy(
                    summary = "A game can have a Wine build of its own once Windows games are set up. Until then it uses the shared one, " +
                        "chosen under Settings > Library > Windows games.",
                    choices = emptyList(),
                )
            } else {
                row
            }
        }
        return WineOptionsState(
            prefixName = "Not set up yet",
            ownPrefix = false,
            ownChoices = choices.size,
            rows = rows + gameRows(context, entryId, gameRoot),
            missing = emptyList(),
            setUp = false,
        )
    }

    private fun setupChoices(context: Context): Map<String, String> = WineGameOptionsPrefs.get(context, SETUP_ENTRY)

    /** [base] with the setup choices ([SETUP_ENTRY]) laid over it. */
    private fun withSetupChoices(base: WineOptionPlan.Settings, chosen: Map<String, String>): WineOptionPlan.Settings =
        WineOptionPlan.merge(base, chosen).copy(wine = chosen[WINE_KEY] ?: base.wine)

    /**
     * The device's [defaults] with the choices made before setup laid over them: what Set up Windows games
     * creates the shared environment from. Preferences reads; never on the main thread.
     */
    fun withSetupChoices(context: Context, defaults: ContainerData): ContainerData {
        val chosen = setupChoices(context)
        return if (chosen.isEmpty()) defaults else write(defaults, withSetupChoices(read(defaults), chosen))
    }

    /** The environment exists now: the choices made before it are its settings, no longer pending. */
    fun setupDone(context: Context) = WineGameOptionsPrefs.set(context, SETUP_ENTRY, emptyMap())

    /**
     * One choice for [entryId] (null: the shared environment). Returns at
     * once; [state] waits for it. A game sharing the prefix stores it as its
     * own choice, except a different Wine build, which makes the game a
     * prefix of its own named [title].
     */
    fun select(context: Context, entryId: String?, title: String?, rowId: String, value: String) {
        val app = context.applicationContext
        val previous = lastWrite
        lastWrite = scope.launch {
            previous?.join()
            runCatching {
                WindowsBackbone.awaitReady(app)
                if (rowId == LSFG && entryId != null) {
                    val stored = WineGameOptionsPrefs.get(app, entryId) - LSFG_KEY
                    WineGameOptionsPrefs.set(app, entryId, if (value == LSFG_OFF) stored else stored + (LSFG_KEY to value))
                    return@runCatching
                }
                if (rowId == STEAMWORKS && entryId != null) {
                    val stored = WineGameOptionsPrefs.get(app, entryId) - STEAMWORKS_KEY
                    WineGameOptionsPrefs.set(app, entryId, stored + (STEAMWORKS_KEY to value))
                    return@runCatching
                }
                if (rowId == STEAMWORKS_APPID && entryId != null) {
                    val stored = WineGameOptionsPrefs.get(app, entryId) - STEAMWORKS_APPID_KEY
                    val appId = value.trim().toIntOrNull()?.takeIf { it > 0 }
                    WineGameOptionsPrefs.set(app, entryId, if (appId != null) stored + (STEAMWORKS_APPID_KEY to appId.toString()) else stored)
                    return@runCatching
                }
                val container = PcContainers.forGame(app, entryId) ?: return@runCatching selectBeforeSetup(app, entryId, rowId, value)
                val data = ContainerUtils.toContainerData(container)
                val shared = read(data)
                if (entryId == null || PcContainers.isOwnPrefix(entryId, container)) {
                    ContainerUtils.applyToContainer(app, container, write(data, apply(app, shared, rowId, value)))
                    return@runCatching
                }
                val current = WineOptionPlan.merge(shared, gameChoices(app, entryId))
                val next = apply(app, current, rowId, value)
                if (next.wine != shared.wine) {
                    PcContainers.createOwn(app, entryId, title ?: entryId, write(data, next))
                    setWineChoices(app, entryId, emptyMap())
                } else {
                    setWineChoices(app, entryId, WineOptionPlan.diff(shared, next))
                }
            }.onFailure { android.util.Log.w(TAG, "Wine option $rowId=$value not saved", it) }
        }
    }

    /** A choice made before Set up Windows games: kept as the setup's choice, or as the game's own. */
    private suspend fun selectBeforeSetup(context: Context, entryId: String?, rowId: String, value: String) {
        val defaults = read(ContainerUtils.deviceDefaultContainerData(context))
        val shared = withSetupChoices(defaults, setupChoices(context))
        if (entryId == null) {
            val next = apply(context, shared, rowId, value)
            val chosen = WineOptionPlan.diff(defaults, next) + (if (next.wine != defaults.wine) mapOf(WINE_KEY to next.wine) else emptyMap())
            WineGameOptionsPrefs.set(context, SETUP_ENTRY, chosen)
        } else if (rowId != WINE) {
            val next = apply(context, WineOptionPlan.merge(shared, gameChoices(context, entryId)), rowId, value)
            setWineChoices(context, entryId, WineOptionPlan.diff(shared, next))
        }
    }

    /** Drops [entryId]'s own choices, so it runs with the shared settings again. */
    suspend fun useShared(context: Context, entryId: String): String = withContext(Dispatchers.IO) {
        lastWrite?.join()
        setWineChoices(context, entryId, emptyMap())
        "This game uses the shared settings again."
    }

    /** Fetches everything [entryId]'s settings name and the device lacks; the line to show when done. */
    suspend fun download(context: Context, entryId: String?, onStatus: (String) -> Unit): String {
        lastWrite?.join()
        val container = withContext(Dispatchers.IO) {
            PcContainers.forGame(context, entryId)?.also { it.setLaunchOverrides(launchOverrides(context, entryId, it)) }
        } ?: return "There is no Windows environment yet. Set up Windows games first."
        return runCatching { WineComponents.ensure(context, container, onStatus) }
            .fold({ "Everything these settings need is on this device." }, { "Download failed: ${it.message ?: it}" })
    }

    /** Stores [wine] as [entryId]'s Wine choices, leaving its own non-prefix choices (frame generation, Steamworks) as they are. */
    private fun setWineChoices(context: Context, entryId: String, wine: Map<String, String>) {
        val own = WineGameOptionsPrefs.get(context, entryId).filterKeys { it in OWN_KEYS }
        WineGameOptionsPrefs.set(context, entryId, wine + own)
    }

    /**
     * What [entryId] (folder [gameRoot]) starts through the Steamworks shim as, or null when it does not: its
     * own choice, else on for a game Steam owns and off for any other ([SteamworksShim.resolve]). Disk work.
     */
    fun steamworksNeed(context: Context, entryId: String?, gameRoot: File?): SteamworksShim.Need? {
        val detected = SteamworksShim.detect(entryId, gameRoot)
        val own = entryId?.let { WineGameOptionsPrefs.get(context, it) }.orEmpty()
        return SteamworksShim.resolve(own[STEAMWORKS_KEY], own[STEAMWORKS_APPID_KEY]?.toIntOrNull(), detected)
    }

    /** The Steamworks rows of [entryId] (docs/SPEC.md 5b, "Steamworks in the prefix"): the switch and the Steam app it starts as. Disk work. */
    private fun steamworksRows(context: Context, entryId: String, gameRoot: File?): List<WineOptionRow> {
        val own = WineGameOptionsPrefs.get(context, entryId)
        val detected = SteamworksShim.detect(entryId, gameRoot)
        val on = (own[STEAMWORKS_KEY] ?: if (detected?.owned == true) STEAMWORKS_ON else STEAMWORKS_OFF) == STEAMWORKS_ON
        val typed = own[STEAMWORKS_APPID_KEY]
        return listOf(
            WineOptionRow(
                STEAMWORKS, "Steamworks",
                "For a game that calls Steam (steam_api.dll): it starts through a stand-in for the Steam client in its prefix (gbe_fork), " +
                    "as your droidtop Steam account when signed in, so it does not stop waiting for Steam. " +
                    "Achievements and multiplayer stay on this device. Nothing in the game's folder changes. " +
                    "Off for games Steam doesn't own; turn on to use the Steamworks shim. A steam_appid.txt only supplies the app ID, the switch decides.",
                if (on) STEAMWORKS_ON else STEAMWORKS_OFF,
                listOf(WineOptionChoice(STEAMWORKS_ON, "On"), WineOptionChoice(STEAMWORKS_OFF, "Off")),
                ownChoice = own[STEAMWORKS_KEY] != null,
            ),
            WineOptionRow(
                STEAMWORKS_APPID, "Steamworks app ID",
                when {
                    typed != null -> "The Steam app ID you set for this game. Clear it to use " +
                        (detected?.let { "the detected one (${it.appId}, from ${it.via})" } ?: "none: this game has no Steam app ID of its own") + "."
                    detected != null -> "Detected from ${detected.via}. Type another number to use that instead."
                    else -> "None found for this game. Type its Steam app ID (the number in its Steam store page address) to use Steamworks with it."
                },
                typed ?: detected?.appId?.toString().orEmpty(),
                emptyList(),
                ownChoice = typed != null,
                text = true,
            ),
        )
    }

    /**
     * The frame generation row of [entryId]: Off unless the game chose a
     * multiplier, and not offered at all (no choices, the summary says why)
     * while droidtop's Steam has no installed Lossless Scaling, which droidtop
     * never downloads for anyone. Disk and database work.
     */
    private suspend fun lsfgRow(context: Context, entryId: String): WineOptionRow {
        val chosen = WineGameOptionsPrefs.get(context, entryId)[LSFG_KEY]
        val installed = LsfgVkManager.losslessFolder(context) != null
        val title = "Frame generation (Lossless Scaling)"
        if (!installed) {
            return WineOptionRow(
                LSFG, title,
                "Lossless Scaling is not installed. Install it from your Steam library in Stores; droidtop never downloads it for you. Until then frame generation stays off.",
                "Off", emptyList(),
            )
        }
        val current = chosen?.let { LsfgVkManager.multiplier(it).toString() } ?: LSFG_OFF
        return WineOptionRow(
            LSFG, title,
            "Shows more frames than the game draws, using your Lossless Scaling install. Costs speed and adds delay; off unless you turn it on for this game.",
            current,
            listOf(WineOptionChoice(LSFG_OFF, "Off")) + LsfgVkManager.MULTIPLIERS.map { WineOptionChoice(it.toString(), "${it}x") },
            ownChoice = chosen != null,
        )
    }

    /** The frame generation launch overrides of [entryId] (`Container.LAUNCH_OVERRIDE_KEYS`): on at its multiplier, or none. */
    private fun lsfgOverrides(context: Context, entryId: String?): Map<String, String> {
        val chosen = entryId?.let { WineGameOptionsPrefs.get(context, it)[LSFG_KEY] } ?: return emptyMap()
        return mapOf(LsfgVkManager.EXTRA_ARMED to "true", LsfgVkManager.EXTRA_MULTIPLIER to LsfgVkManager.multiplier(chosen).toString())
    }

    /**
     * The launch-time values [entryId]'s own choices put over [container]
     * (`Container.setLaunchOverrides` keys): its Wine choices, unless it runs
     * in a prefix of its own, and its frame generation. Disk work.
     */
    fun launchOverrides(context: Context, entryId: String?, container: Container): Map<String, String> =
        wineOverrides(context, entryId, container) + lsfgOverrides(context, entryId)

    private fun wineOverrides(context: Context, entryId: String?, container: Container): Map<String, String> {
        if (entryId == null || PcContainers.isOwnPrefix(entryId, container)) return emptyMap()
        val choices = gameChoices(context, entryId)
        if (choices.isEmpty()) return emptyMap()
        val data = ContainerUtils.toContainerData(container)
        return overrides(data, WineOptionPlan.merge(read(data), choices))
    }

    private fun gameChoices(context: Context, entryId: String): Map<String, String> =
        WineGameOptionsPrefs.get(context, entryId).filterKeys { it in WineOptionPlan.GAME_KEYS }

    /** The container fields [settings] changes from [data], by the container's own JSON keys. */
    private fun overrides(data: ContainerData, settings: WineOptionPlan.Settings): Map<String, String> {
        val next = write(data, settings)
        return buildMap {
            if (next.graphicsDriver != data.graphicsDriver) put("graphicsDriver", next.graphicsDriver)
            if (next.graphicsDriverConfig != data.graphicsDriverConfig) put("graphicsDriverConfig", next.graphicsDriverConfig)
            if (next.dxwrapper != data.dxwrapper) put("dxwrapper", next.dxwrapper)
            if (next.dxwrapperConfig != data.dxwrapperConfig) put("dxwrapperConfig", next.dxwrapperConfig)
            if (next.emulator != data.emulator) put("emulator", next.emulator)
            if (next.box64Version != data.box64Version) put("box64Version", next.box64Version)
            if (next.fexcoreVersion != data.fexcoreVersion) put("fexcoreVersion", next.fexcoreVersion)
        }
    }

    private fun read(data: ContainerData): WineOptionPlan.Settings {
        val dx = KeyValueSet(data.dxwrapperConfig)
        return WineOptionPlan.Settings(
            wine = data.wineVersion,
            emulator = data.emulator,
            box64 = data.box64Version,
            fexcore = data.fexcoreVersion,
            driver = data.graphicsDriver,
            driverVersion = KeyValueSet(data.graphicsDriverConfig).get("version").ifEmpty { DefaultVersion.WRAPPER },
            dxwrapper = WineOptionPlan.dxwrapperKind(data.dxwrapper),
            dxvk = dx.get("version"),
            vkd3d = dx.get("vkd3dVersion"),
        )
    }

    private fun write(data: ContainerData, s: WineOptionPlan.Settings): ContainerData {
        val driverConfig = KeyValueSet(data.graphicsDriverConfig).apply { put("version", s.driverVersion) }
        val dxConfig = KeyValueSet(data.dxwrapperConfig).apply {
            put("version", s.dxvk)
            put("vkd3dVersion", s.vkd3d)
        }
        return data.copy(
            wineVersion = s.wine,
            emulator = s.emulator,
            box64Version = s.box64,
            fexcoreVersion = s.fexcore,
            graphicsDriver = s.driver,
            graphicsDriverConfig = driverConfig.toString(),
            dxwrapper = s.dxwrapper,
            dxwrapperConfig = dxConfig.toString(),
        )
    }

    private suspend fun apply(context: Context, s: WineOptionPlan.Settings, rowId: String, value: String): WineOptionPlan.Settings {
        val x86 = X86_64GuestLibs.isX86_64Host()
        return when (rowId) {
            WINE -> {
                val lists = Lists.load(context)
                val box64 = if (WineOptionPlan.box64IsWowBox64(value)) lists.wowBox64 else lists.box64
                WineOptionPlan.withWine(s, value, x86, box64.map { it.value })
            }
            EMULATOR -> s.copy(emulator = value)
            FEXCORE -> s.copy(fexcore = value)
            BOX64 -> s.copy(box64 = value)
            DRIVER -> s.copy(driver = value)
            DRIVER_VERSION -> s.copy(driverVersion = value)
            DXWRAPPER -> WineOptionPlan.withDxwrapper(s, value, Lists.load(context).dxvk.map { it.value })
            DXVK -> s.copy(dxvk = value)
            VKD3D -> s.copy(vkd3d = value)
            else -> s
        }
    }

    /** [rows], each marked when it is a game's own choice ([ownKeys], [WineOptionPlan.GAME_KEYS] names). */
    private fun rows(context: Context, s: WineOptionPlan.Settings, lists: Lists, ownKeys: Set<String>): List<WineOptionRow> =
        rows(context, s, lists).map { row -> if (GAME_KEY[row.id] in ownKeys) row.copy(ownChoice = true) else row }

    /** Each row's [WineOptionPlan.GAME_KEYS] name; the Wine build has none. */
    private val GAME_KEY = mapOf(
        EMULATOR to "emulator", FEXCORE to "fexcore", BOX64 to "box64", DRIVER to "driver",
        DRIVER_VERSION to "driverVersion", DXWRAPPER to "dxwrapper", DXVK to "dxvk", VKD3D to "vkd3d",
    )

    private fun rows(context: Context, s: WineOptionPlan.Settings, lists: Lists): List<WineOptionRow> = buildList {
        val x86 = X86_64GuestLibs.isX86_64Host()
        val wines = WineOptionPlan.wineBuilds(x86, lists.wine.map { it.value })
            .mapNotNull { id -> lists.wine.firstOrNull { it.value == id } }
            .map { it.copy(label = wineLabel(it.value, it.label, x86)) }
        add(
            row(
                WINE, "Wine build",
                if (x86) "x86_64 builds from Proton 10 on run directly on this device; earlier ones are made for Box64 on ARM and are not offered" else
                    "An ARM (arm64ec) build runs Windows code through FEXCore or Box64 inside Wine; an x86_64 build runs the whole of Wine under Box64",
                s.wine, wines,
            ),
        )
        val emulators = WineOptionPlan.emulators(x86, s.wine)
        when {
            x86 -> add(row(EMULATOR, "x86 emulation", "None needed: this device runs x86_64 code itself", "None", emptyList()))
            emulators.isEmpty() -> add(
                row(EMULATOR, "x86 emulation", "An x86_64 Wine build runs under Box64; FEXCore needs an ARM (arm64ec) build", WineOptionPlan.BOX64, emptyList()),
            )
            else -> add(
                row(
                    EMULATOR, "x86 emulation for 32-bit programs",
                    "64-bit programs always run through FEXCore in an ARM Wine build",
                    s.emulator, emulators.map { WineOptionChoice(it, it) },
                ),
            )
        }
        if (WineOptionPlan.usesFexcore(x86, s.wine)) {
            add(row(FEXCORE, "FEXCore version", "The FEX build Wine loads", s.fexcore, lists.fexcore))
        }
        if (WineOptionPlan.usesBox64(x86, s)) {
            val wow = WineOptionPlan.box64IsWowBox64(s.wine)
            add(
                row(
                    BOX64, if (wow) "WowBox64 version" else "Box64 version",
                    if (wow) "The Box64 build Wine loads for 32-bit programs" else "The Box64 build that runs Wine",
                    s.box64, if (wow) lists.wowBox64 else lists.box64,
                ),
            )
        }
        add(
            row(
                DRIVER, "Graphics driver",
                if (x86) "Lavapipe (software Vulkan) works on every x86_64 device and is slow; None draws 2D and GDI only" else
                    "How Wine reaches the GPU",
                s.driver, if (x86) lists.x86Drivers else lists.drivers,
            ),
        )
        if (WineOptionPlan.usesDriverVersion(x86, s.driver)) {
            add(row(DRIVER_VERSION, "Driver build", "System uses the device's own driver; the others are Turnip and Qualcomm builds", s.driverVersion, lists.driverVersions))
        }
        add(row(DXWRAPPER, "Direct3D", "What turns Direct3D into something the driver understands", s.dxwrapper, lists.dxwrappers))
        if (WineOptionPlan.usesDxvk(s.dxwrapper)) {
            val versions = WineOptionPlan.dxvkVersions(s.dxwrapper, lists.dxvk.map { it.value }).toSet()
            add(row(DXVK, "DXVK version", "Direct3D 8 to 11", s.dxvk, lists.dxvk.filter { it.value in versions }))
        }
        if (WineOptionPlan.usesVkd3d(s.dxwrapper)) {
            add(row(VKD3D, "VKD3D version", "Direct3D 12", s.vkd3d, lists.vkd3d))
        }
    }

    /** A row whose current value is always one of its choices, so the picker can show it. */
    private fun row(id: String, title: String, summary: String, current: String, choices: List<WineOptionChoice>): WineOptionRow {
        val all = if (choices.isEmpty() || choices.any { it.value == current }) choices else listOf(WineOptionChoice(current, current)) + choices
        return WineOptionRow(id, title, summary, current, all)
    }

    private fun wineLabel(id: String, label: String, x86: Boolean): String {
        val kind = when {
            WineOptionPlan.isArm64ec(id) -> " - ARM build"
            !x86 -> " - x86_64 build, under Box64"
            else -> ""
        }
        return label.replace(id, id + kind)
    }

    /** Every list the rows choose from, read once per screen. */
    private class Lists(
        val wine: List<WineOptionChoice>,
        val box64: List<WineOptionChoice>,
        val wowBox64: List<WineOptionChoice>,
        val fexcore: List<WineOptionChoice>,
        val drivers: List<WineOptionChoice>,
        val x86Drivers: List<WineOptionChoice>,
        val driverVersions: List<WineOptionChoice>,
        val dxwrappers: List<WineOptionChoice>,
        val dxvk: List<WineOptionChoice>,
        val vkd3d: List<WineOptionChoice>,
    ) {
        companion object {
            suspend fun load(context: Context): Lists {
                val res = context.resources
                val availability = ManifestComponentHelper.loadComponentAvailability(context)
                val installed = availability.installed
                val sourceLabels = availability.manifest.sources.associate { it.id to it.label }
                fun manifest(type: String): List<ManifestEntry> =
                    ManifestComponentHelper.filterManifestByVariant(availability.manifest.items[type].orEmpty(), "bionic")
                // Not on the device yet: says so, and names the source when it
                // is not droidtop's own mirror ("Banners-Turnip, downloads when used").
                fun versions(base: List<String>, have: List<String>, type: String): List<WineOptionChoice> {
                    val entries = manifest(type)
                    val list = ManifestComponentHelper.buildVersionOptionList(base.map(::bare), have, entries)
                    return list.ids.indices.map { i ->
                        val entry = entries.firstOrNull { it.id == list.ids[i] }
                        val label = entry?.name?.takeIf { type == ManifestContentTypes.DRIVER && it != list.ids[i] }
                            ?.let { "$it (${list.labels[i]})" } ?: list.labels[i]
                        val note = listOfNotNull(
                            entry?.source?.takeIf { it != ComponentCatalog.SOURCE_MIRROR }?.let { sourceLabels[it] ?: it },
                            NOT_HERE.takeIf { list.muted[i] },
                        )
                        WineOptionChoice(list.ids[i], if (note.isEmpty()) label else "$label (${note.joinToString(", ")})")
                    }
                }
                val bundledWine = res.getStringArray(R.array.bionic_wine_entries).toList()
                val wine = versions(bundledWine, installed.proton + installed.wine, ManifestContentTypes.PROTON) +
                    versions(emptyList(), emptyList(), ManifestContentTypes.WINE)
                val bionicDrivers = res.getStringArray(R.array.bionic_graphics_driver_entries).toList() + installed.wrapper
                return Lists(
                    wine = wine.distinctBy { it.value }.map { choice ->
                        // A bundled Proton 9 build is installed by setup, not by
                        // the component list; say so only when it is not there.
                        if (choice.value in bundledWine && !WineComponents.wineBinary(context, choice.value).isFile) {
                            choice.copy(label = "${choice.label} ($NOT_HERE)")
                        } else {
                            choice
                        }
                    },
                    box64 = versions(res.getStringArray(R.array.box64_bionic_version_entries).toList(), installed.box64, ManifestContentTypes.BOX64),
                    wowBox64 = versions(res.getStringArray(R.array.wowbox64_version_entries).toList(), installed.wowBox64, ManifestContentTypes.WOWBOX64),
                    fexcore = versions(res.getStringArray(R.array.fexcore_version_entries).toList(), installed.fexcore, ManifestContentTypes.FEXCORE),
                    drivers = bionicDrivers.distinct().map { WineOptionChoice(StringUtils.parseIdentifier(it), it) },
                    // The names gamenative's own prefix dialog shows too (X86_64Graphics.label).
                    x86Drivers = X86_64Graphics.DRIVERS.map { id ->
                        WineOptionChoice(
                            id,
                            X86_64Graphics.label(id) + if (X86_64Graphics.isInstalled(context, id)) "" else " ($NOT_HERE)",
                        )
                    },
                    driverVersions = versions(
                        ManifestComponentHelper.bundledGraphicsDriverBase(res.getStringArray(R.array.wrapper_graphics_driver_version_entries).toList()),
                        availability.installedDrivers,
                        ManifestContentTypes.DRIVER,
                    ),
                    dxwrappers = res.getStringArray(R.array.dxwrapper_entries).map { WineOptionChoice(StringUtils.parseIdentifier(it), it) },
                    dxvk = versions(
                        ManifestComponentHelper.bundledDxWrapperBase(res.getStringArray(R.array.dxvk_version_entries).toList()),
                        installed.dxvk,
                        ManifestContentTypes.DXVK,
                    ),
                    vkd3d = versions(
                        ManifestComponentHelper.bundledDxWrapperBase(res.getStringArray(R.array.vkd3d_version_entries).toList()),
                        installed.vkd3d,
                        ManifestContentTypes.VKD3D,
                    ),
                )
            }

            /** "0.3.7 (Default)" in the bundled list is the version "0.3.7". */
            private fun bare(entry: String): String = entry.replace(Regex("\\s*\\([^)]*\\)$"), "")
        }
    }

    private const val TAG = "droidtop.WineOptions"
}
