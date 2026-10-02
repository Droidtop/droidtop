package dev.droidtop.runtime.windows

import android.content.Context
import app.gamenative.R
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.ManifestComponentHelper
import app.gamenative.utils.ManifestContentTypes
import app.gamenative.utils.ManifestEntry
import app.gamenative.utils.X86_64GuestLibs
import app.gamenative.utils.X86_64Graphics
import com.winlator.container.ContainerData
import com.winlator.core.DefaultVersion
import com.winlator.core.KeyValueSet
import com.winlator.core.StringUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
)

/**
 * The Wine options of the prefix a game (or, with no game, every game
 * without settings of its own) runs in. [ownPrefix] says which of the two it
 * is; [missing] names what the settings need that is not on the device yet.
 */
data class WineOptionsState(
    val prefixName: String,
    val ownPrefix: Boolean,
    val rows: List<WineOptionRow>,
    val missing: List<String>,
)

/**
 * Wine build, x86 emulation, graphics driver and Direct3D translation, per
 * prefix (docs/SPEC.md 5a): the rows Settings > Windows games shows for the
 * shared environment and a game's Wine settings show for that game.
 *
 * The prefix is the one store: every option is a field of gamenative's
 * `Container` that its launcher already reads, written through
 * [ContainerUtils.applyToContainer], the path gamenative's own configuration
 * dialog saves through. What is offered is read from the fork, not listed
 * here: its bundled versions, what is installed, and upstream GameNative's
 * component list ([ManifestComponentHelper.loadComponentAvailability]); which
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

    private const val NOT_HERE = " (downloads when used)"

    // Settings rows choose synchronously on the main thread; the write is
    // disk work, so it runs here, and the next read waits for it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastWrite: Job? = null

    /** Null when there is no Windows environment yet (the setup step, not an error). */
    suspend fun state(context: Context, entryId: String?): WineOptionsState? = withContext(Dispatchers.IO) {
        lastWrite?.join()
        WindowsBackbone.awaitReady(context)
        val container = PcContainers.forGame(context, entryId) ?: return@withContext null
        val lists = Lists.load(context)
        val settings = read(ContainerUtils.toContainerData(container))
        WineOptionsState(
            prefixName = container.name?.takeIf { it.isNotBlank() } ?: container.id,
            ownPrefix = entryId != null && PcContainers.isOwnPrefix(entryId, container),
            rows = rows(context, settings, lists),
            missing = runCatching { WineComponents.missing(context, container) }.getOrDefault(emptyList()),
        )
    }

    /** Writes one choice into the prefix [entryId] runs in. Returns at once; [state] waits for it. */
    fun select(context: Context, entryId: String?, rowId: String, value: String) {
        val app = context.applicationContext
        val previous = lastWrite
        lastWrite = scope.launch {
            previous?.join()
            runCatching {
                WindowsBackbone.awaitReady(app)
                val container = PcContainers.forGame(app, entryId) ?: return@runCatching
                val data = ContainerUtils.toContainerData(container)
                val next = apply(app, read(data), rowId, value)
                ContainerUtils.applyToContainer(app, container, write(data, next))
            }.onFailure { android.util.Log.w(TAG, "Wine option $rowId=$value not saved", it) }
        }
    }

    /** Fetches everything the prefix's settings name and the device lacks; the line to show when done. */
    suspend fun download(context: Context, entryId: String?, onStatus: (String) -> Unit): String {
        lastWrite?.join()
        val container = withContext(Dispatchers.IO) { PcContainers.forGame(context, entryId) }
            ?: return "There is no Windows environment yet. Set up Windows games first."
        return runCatching { WineComponents.ensure(context, container, onStatus) }
            .fold({ "Everything these settings need is on this device." }, { "Download failed: ${it.message ?: it}" })
    }

    /** Gives [entryId] a prefix of its own with the shared settings as its start. The line to show when done. */
    suspend fun useOwnPrefix(context: Context, entryId: String, title: String, onStatus: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            lastWrite?.join()
            onStatus("Making $title's own prefix…")
            runCatching { PcContainers.createOwn(context, entryId, title) }
                .fold({ "$title now has Wine settings of its own." }, { "Couldn't make $title's own prefix: ${it.message ?: it}" })
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

    private fun rows(context: Context, s: WineOptionPlan.Settings, lists: Lists): List<WineOptionRow> = buildList {
        val x86 = X86_64GuestLibs.isX86_64Host()
        val wines = WineOptionPlan.wineBuilds(x86, lists.wine.map { it.value })
            .mapNotNull { id -> lists.wine.firstOrNull { it.value == id } }
            .map { it.copy(label = wineLabel(it.value, it.label, x86)) }
        add(
            row(
                WINE, "Wine build",
                if (x86) "x86_64 builds run directly on this device" else
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
                if (x86) "Software Vulkan works on every x86_64 device and is slow; None draws 2D and GDI only" else
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
                fun manifest(type: String): List<ManifestEntry> =
                    ManifestComponentHelper.filterManifestByVariant(availability.manifest.items[type].orEmpty(), "bionic")
                fun versions(base: List<String>, have: List<String>, type: String): List<WineOptionChoice> {
                    val list = ManifestComponentHelper.buildVersionOptionList(base.map(::bare), have, manifest(type))
                    return list.ids.indices.map { i ->
                        WineOptionChoice(list.ids[i], list.labels[i] + if (list.muted[i]) NOT_HERE else "")
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
                            choice.copy(label = choice.label + NOT_HERE)
                        } else {
                            choice
                        }
                    },
                    box64 = versions(res.getStringArray(R.array.box64_bionic_version_entries).toList(), installed.box64, ManifestContentTypes.BOX64),
                    wowBox64 = versions(res.getStringArray(R.array.wowbox64_version_entries).toList(), installed.wowBox64, ManifestContentTypes.WOWBOX64),
                    fexcore = versions(res.getStringArray(R.array.fexcore_version_entries).toList(), installed.fexcore, ManifestContentTypes.FEXCORE),
                    drivers = bionicDrivers.distinct().map { WineOptionChoice(StringUtils.parseIdentifier(it), it) },
                    x86Drivers = X86_64Graphics.DRIVERS.map { id ->
                        WineOptionChoice(
                            id,
                            when (id) {
                                X86_64Graphics.LAVAPIPE -> "Software Vulkan (lavapipe)" + if (X86_64Graphics.isInstalled(context, id)) "" else NOT_HERE
                                else -> "None (2D and GDI only)"
                            },
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
