package dev.droidtop.runtime.windows

import android.content.Context
import dev.droidtop.runtime.windows.R
import dev.droidtop.runtime.windows.utils.ContainerUtils
import com.winlator.box86_64.Box86_64PresetManager
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import com.winlator.core.StringUtils
import com.winlator.core.envvars.EnvVars
import com.winlator.fexcore.FEXCorePresetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of a prefix's settings, as plain values: `:app` draws it, this module writes it. */
sealed interface PrefixSetting {
    val id: String
    val title: String

    data class Choice(override val id: String, override val title: String, val current: String, val choices: List<WineOptionChoice>) : PrefixSetting
    data class Toggle(override val id: String, override val title: String, val current: Boolean) : PrefixSetting
    data class Text(override val id: String, override val title: String, val current: String) : PrefixSetting
    data class Slider(override val id: String, override val title: String, val current: Int, val min: Int, val max: Int) : PrefixSetting
    data class Info(override val id: String, override val title: String, val value: String) : PrefixSetting
}

data class PrefixSection(val id: String, val title: String, val rows: List<PrefixSetting>)

data class PrefixSettingsState(val prefixName: String, val sections: List<PrefixSection>)

/**
 * Every setting of a Wine prefix beyond the Wine build, emulation, driver and
 * Direct3D rows ([WineOptions]): display, audio, controller, CPU, Wine's
 * registry values, Windows components, environment variables and drives
 * (docs/SPEC.md 7c). droidtop's own rows, replacing GameNative's
 * `ContainerConfigDialog`; written through the same
 * [ContainerUtils.applyToContainer] path that dialog saved through, so a
 * prefix configured either way reads back the same. Only fields droidtop's
 * launch reads are offered: Steam-, XR- and store-only fields are not.
 *
 * [entryId] picks the prefix the way the launch does ([PcContainers.forGame]):
 * null is the shared environment.
 */
object PrefixSettings {

    // Choice and slider rows set synchronously on the main thread; the write
    // is disk work, so it runs here, in order, and the next read waits for it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastWrite: Job? = null

    /** Null when there is no Windows environment yet. Disk work. */
    suspend fun state(context: Context, entryId: String?): PrefixSettingsState? = withContext(Dispatchers.IO) {
        lastWrite?.join()
        WindowsBackbone.awaitReady(context)
        val container = PcContainers.forGame(context, entryId) ?: return@withContext null
        val data = ContainerUtils.toContainerData(container)
        val drives = container.drivesIterator().map { Pair(it[0], it[1]) }
        PrefixSettingsState(
            prefixName = container.name?.takeIf { it.isNotBlank() } ?: container.id,
            sections = sections(context, data, drives),
        )
    }

    /** Sets row [id] to [value] for [entryId]'s prefix. Returns at once; [state] waits for it, and so can the caller. */
    fun set(context: Context, entryId: String?, id: String, value: String): Job {
        val app = context.applicationContext
        val previous = lastWrite
        val job = scope.launch {
            previous?.join()
            runCatching {
                WindowsBackbone.awaitReady(app)
                val container = PcContainers.forGame(app, entryId) ?: return@runCatching
                val data = ContainerUtils.toContainerData(container)
                val next = update(app, data, id, value) ?: return@runCatching
                if (next != data) ContainerUtils.applyToContainer(app, container, next)
            }.onFailure { android.util.Log.w(TAG, "prefix setting $id not saved", it) }
        }
        lastWrite = job
        return job
    }

    private fun sections(context: Context, d: ContainerData, drives: List<Pair<String, String>>): List<PrefixSection> {
        val res = context.resources
        val env = EnvVars(d.envVars)
        return listOf(
            PrefixSection(
                "display", "Display",
                buildList {
                    add(
                        choice(
                            SCREEN_SIZE, "Screen size", d.screenSize,
                            // The first entry is the dialog's "Custom"; a custom size shows as itself.
                            res.getStringArray(R.array.screen_size_entries).drop(1).map { WineOptionChoice(it.substringBefore(' '), it) },
                        ),
                    )
                    add(
                        choice(
                            DISPLAY_RENDERER, "Renderer", d.displayRenderer,
                            res.getStringArray(R.array.displayrenderers_entries).map { WineOptionChoice(StringUtils.parseIdentifier(it), it) },
                        ),
                    )
                    add(choice(PRESENT_MODE, "Present mode", d.rendererPresentMode, listOf(WineOptionChoice("fifo", "FIFO"), WineOptionChoice("mailbox", "Mailbox"))))
                    if (d.displayRenderer.equals("surfaceflinger", ignoreCase = true)) {
                        add(PrefixSetting.Toggle(SF_COMPAT, "SurfaceFlinger compatibility", d.sfCompatMode))
                    }
                    val effects = res.getStringArray(R.array.vkbasalt_sharpness_entries)
                    val labels = res.getStringArray(R.array.vkbasalt_sharpness_labels)
                    add(choice(SHARPNESS, "Sharpening", d.sharpnessEffect, effects.mapIndexed { i, e -> WineOptionChoice(e, labels.getOrElse(i) { e }) }))
                    if (!d.sharpnessEffect.equals("None", ignoreCase = true)) {
                        add(PrefixSetting.Slider(SHARPNESS_LEVEL, "Sharpening strength", d.sharpnessLevel, 0, 100))
                        add(PrefixSetting.Slider(SHARPNESS_DENOISE, "Sharpening denoise", d.sharpnessDenoise, 0, 100))
                    }
                },
            ),
            PrefixSection(
                "audio", "Audio",
                buildList {
                    add(
                        choice(
                            AUDIO_DRIVER, "Audio driver", d.audioDriver,
                            res.getStringArray(R.array.audio_driver_entries).map { WineOptionChoice(StringUtils.parseIdentifier(it), it) },
                        ),
                    )
                    if (d.audioDriver.equals("pulseaudio", ignoreCase = true)) {
                        add(PrefixSetting.Toggle(PULSE_LOW_LATENCY, "Low latency", d.pulseaudioLowLatency))
                    }
                },
            ),
            PrefixSection(
                "controller", "Controller",
                listOf(
                    PrefixSetting.Toggle(SDL_API, "Controllers through SDL", d.sdlControllerAPI),
                    PrefixSetting.Toggle(XINPUT, "XInput", d.enableXInput),
                    PrefixSetting.Toggle(DINPUT, "DirectInput", d.enableDInput),
                    choice(
                        DINPUT_MAPPER, "DirectInput mapping", d.dinputMapperType.toString(),
                        listOf(WineOptionChoice("1", "Standard"), WineOptionChoice("2", "XInput mapper")),
                    ),
                    PrefixSetting.Toggle(DISABLE_MOUSE, "Ignore the mouse", d.disableMouseInput),
                    PrefixSetting.Toggle(TOUCHSCREEN, "Touchscreen mode", d.touchscreenMode),
                ),
            ),
            PrefixSection(
                "cpu", "CPU",
                listOf(
                    choice(
                        STARTUP_SELECTION, "Windows services at start", d.startupSelection.toString(),
                        res.getStringArray(R.array.startup_selection_entries).mapIndexed { i, e -> WineOptionChoice(i.toString(), e) },
                    ),
                    PrefixSetting.Text(CPU_LIST, "CPU cores", d.cpuList),
                    PrefixSetting.Text(CPU_LIST_WOW64, "CPU cores for 32-bit programs", d.cpuListWoW64),
                    choice(
                        BOX64_PRESET, "Box64 preset", d.box64Preset,
                        Box86_64PresetManager.getPresets("box64", context).map { WineOptionChoice(it.id, it.name) },
                    ),
                    choice(
                        FEXCORE_PRESET, "FEXCore preset", d.fexcorePreset,
                        FEXCorePresetManager.getPresets(context).map { WineOptionChoice(it.id, it.name) },
                    ),
                ),
            ),
            PrefixSection(
                "wine", "Wine",
                listOf(
                    choice(
                        GPU_NAME, "Reported GPU", d.videoPciDeviceID.toString(),
                        ContainerUtils.getGPUCards(context).values.map { WineOptionChoice(it.deviceId.toString(), it.name) },
                    ),
                    choice(
                        OFFSCREEN, "Offscreen rendering", d.offScreenRenderingMode,
                        res.getStringArray(R.array.offscreen_rendering_modes).map { WineOptionChoice(it.lowercase(), it) },
                    ),
                    choice(
                        VIDEO_MEMORY, "Video memory", d.videoMemorySize,
                        res.getStringArray(R.array.video_memory_size_entries).map { WineOptionChoice(digits(it), it) },
                    ),
                    PrefixSetting.Toggle(CSMT, "CSMT", d.csmt),
                    PrefixSetting.Toggle(STRICT_SHADER_MATH, "Strict shader math", d.strictShaderMath),
                    choice(
                        MOUSE_WARP, "Mouse warp", d.mouseWarpOverride,
                        res.getStringArray(R.array.mouse_warp_override_entries).map { WineOptionChoice(it.lowercase(), it) },
                    ),
                    PrefixSetting.Text(EXEC_ARGS, "Extra arguments", d.execArgs),
                ),
            ),
            PrefixSection(
                "components", "Windows components",
                KeyValueSet(d.wincomponents).map { (id, on) ->
                    choice(
                        COMPONENT + id, COMPONENT_NAMES[id] ?: id, on,
                        listOf(WineOptionChoice("0", "Wine's own"), WineOptionChoice("1", "Windows' own")),
                    )
                },
            ),
            PrefixSection(
                "environment", "Environment",
                env.map { name -> PrefixSetting.Text(ENV + name, name, env.get(name)) } +
                    PrefixSetting.Text(ENV_ADD, "Add a variable (NAME=value)", ""),
            ),
            PrefixSection(
                "drives", "Drives",
                drives.map { (letter, path) -> PrefixSetting.Info(DRIVE + letter, "$letter:", path) },
            ),
        )
    }

    /** [data] with row [id] set to [value]; null for a row this does not know or a value it cannot read. */
    private fun update(context: Context, data: ContainerData, id: String, value: String): ContainerData? {
        fun on() = value.toBooleanStrictOrNull()
        return when {
            id == SCREEN_SIZE -> data.copy(screenSize = value)
            id == DISPLAY_RENDERER -> data.copy(displayRenderer = value)
            id == PRESENT_MODE -> data.copy(rendererPresentMode = value)
            id == SF_COMPAT -> on()?.let { data.copy(sfCompatMode = it) }
            id == SHARPNESS -> data.copy(sharpnessEffect = value)
            id == SHARPNESS_LEVEL -> value.toIntOrNull()?.let { data.copy(sharpnessLevel = it.coerceIn(0, 100)) }
            id == SHARPNESS_DENOISE -> value.toIntOrNull()?.let { data.copy(sharpnessDenoise = it.coerceIn(0, 100)) }
            id == AUDIO_DRIVER -> data.copy(audioDriver = value)
            id == PULSE_LOW_LATENCY -> on()?.let { data.copy(pulseaudioLowLatency = it) }
            id == SDL_API -> on()?.let { data.copy(sdlControllerAPI = it) }
            id == XINPUT -> on()?.let { data.copy(enableXInput = it) }
            id == DINPUT -> on()?.let { data.copy(enableDInput = it) }
            id == DINPUT_MAPPER -> value.toByteOrNull()?.let { data.copy(dinputMapperType = it) }
            id == DISABLE_MOUSE -> on()?.let { data.copy(disableMouseInput = it) }
            id == TOUCHSCREEN -> on()?.let { data.copy(touchscreenMode = it) }
            id == STARTUP_SELECTION -> value.toByteOrNull()?.let { data.copy(startupSelection = it) }
            id == CPU_LIST -> cpuList(value)?.let { data.copy(cpuList = it) }
            id == CPU_LIST_WOW64 -> cpuList(value)?.let { data.copy(cpuListWoW64 = it) }
            id == BOX64_PRESET -> data.copy(box64Preset = value)
            id == FEXCORE_PRESET -> data.copy(fexcorePreset = value)
            id == GPU_NAME -> value.toIntOrNull()?.let { data.copy(videoPciDeviceID = it) }
            id == OFFSCREEN -> data.copy(offScreenRenderingMode = value)
            id == VIDEO_MEMORY -> data.copy(videoMemorySize = value)
            id == CSMT -> on()?.let { data.copy(csmt = it) }
            id == STRICT_SHADER_MATH -> on()?.let { data.copy(strictShaderMath = it) }
            id == MOUSE_WARP -> data.copy(mouseWarpOverride = value)
            id == EXEC_ARGS -> data.copy(execArgs = value.trim())
            id.startsWith(COMPONENT) -> {
                val components = KeyValueSet(data.wincomponents)
                components.put(id.removePrefix(COMPONENT), if (value == "1") "1" else "0")
                data.copy(wincomponents = components.toString())
            }
            id == ENV_ADD -> {
                val name = value.substringBefore('=').trim()
                if (name.isEmpty() || !value.contains('=') || name.any { it.isWhitespace() }) return null
                val env = EnvVars(data.envVars)
                env.put(name, value.substringAfter('=').trim())
                data.copy(envVars = env.toString())
            }
            id.startsWith(ENV) -> {
                val env = EnvVars(data.envVars)
                val name = id.removePrefix(ENV)
                // An emptied value removes the variable.
                if (value.isBlank()) env.remove(name) else env.put(name, value.trim())
                data.copy(envVars = env.toString())
            }
            else -> null
        }
    }

    /** "0,1,2,3": core numbers separated by commas; null when it is anything else. */
    private fun cpuList(value: String): String? {
        val cores = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (cores.isEmpty() || cores.any { it.toIntOrNull() == null }) return null
        return cores.joinToString(",")
    }

    private fun digits(entry: String): String = entry.takeWhile { it.isDigit() }

    /** A choice whose current value is always among its choices, so the picker can show it. */
    private fun choice(id: String, title: String, current: String, choices: List<WineOptionChoice>): PrefixSetting.Choice {
        val all = if (choices.isEmpty() || choices.any { it.value == current }) choices else listOf(WineOptionChoice(current, current)) + choices
        return PrefixSetting.Choice(id, title, current, all)
    }

    private val COMPONENT_NAMES = mapOf(
        "direct3d" to "Direct3D", "directsound" to "DirectSound", "directinput8" to "DirectInput 8",
        "directinput" to "DirectInput", "directmusic" to "DirectMusic", "directplay" to "DirectPlay",
        "directshow" to "DirectShow", "directx" to "DirectX", "vcrun2010" to "Visual C++ 2010",
        "wmdecoder" to "Windows Media decoder", "opengl" to "OpenGL",
    )

    const val SCREEN_SIZE = "prefix_screen_size"
    const val DISPLAY_RENDERER = "prefix_display_renderer"
    const val PRESENT_MODE = "prefix_present_mode"
    const val SF_COMPAT = "prefix_sf_compat"
    const val SHARPNESS = "prefix_sharpness"
    const val SHARPNESS_LEVEL = "prefix_sharpness_level"
    const val SHARPNESS_DENOISE = "prefix_sharpness_denoise"
    const val AUDIO_DRIVER = "prefix_audio_driver"
    const val PULSE_LOW_LATENCY = "prefix_pulse_low_latency"
    const val SDL_API = "prefix_sdl_api"
    const val XINPUT = "prefix_xinput"
    const val DINPUT = "prefix_dinput"
    const val DINPUT_MAPPER = "prefix_dinput_mapper"
    const val DISABLE_MOUSE = "prefix_disable_mouse"
    const val TOUCHSCREEN = "prefix_touchscreen"
    const val STARTUP_SELECTION = "prefix_startup_selection"
    const val CPU_LIST = "prefix_cpu_list"
    const val CPU_LIST_WOW64 = "prefix_cpu_list_wow64"
    const val BOX64_PRESET = "prefix_box64_preset"
    const val FEXCORE_PRESET = "prefix_fexcore_preset"
    const val GPU_NAME = "prefix_gpu_name"
    const val OFFSCREEN = "prefix_offscreen"
    const val VIDEO_MEMORY = "prefix_video_memory"
    const val CSMT = "prefix_csmt"
    const val STRICT_SHADER_MATH = "prefix_strict_shader_math"
    const val MOUSE_WARP = "prefix_mouse_warp"
    const val EXEC_ARGS = "prefix_exec_args"
    const val COMPONENT = "prefix_component_"
    const val ENV = "prefix_env_"
    const val ENV_ADD = "prefix_env_add"
    const val DRIVE = "prefix_drive_"

    private const val TAG = "droidtop.PrefixSettings"
}
