package dev.droidtop.runtime.windows.utils

import android.content.Context
import android.os.Build
import dev.droidtop.runtime.windows.BuildConfig
import dev.droidtop.runtime.windows.PrefManager
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.core.DefaultVersion
import com.winlator.core.FileUtils
import com.winlator.core.KeyValueSet
import com.winlator.core.GPUInformation
import com.winlator.core.envvars.EnvVars
import com.winlator.core.WineRegistryEditor
import com.winlator.core.WineThemeManager
import com.winlator.winhandler.WinHandler.PreferredInputApi
import com.winlator.xenvironment.ImageFs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * A container's settings in and out of [ContainerData], and this device's
 * defaults. GameNative's app/gamenative/utils/ContainerUtils.kt (GPL-3.0), cut
 * to what droidtop's runtime calls; GameNative's per-store container creation
 * and its Steam DLL markers are not carried.
 */
object ContainerUtils {
    /** GameNative's LsfgVkManager.EXTRA_ARMED: kept so a prefix's own value survives a save. */
    private const val LSFG_ENABLED_EXTRA = "lsfgEnabled"

    data class GpuInfo(
        val deviceId: Int,
        val vendorId: Int,
        val name: String,
    )

    const val WRAPPER_TURNIP_CAPABLE = "Turnip v26.2.0 R4"
    const val WRAPPER_ADRENO_8ELITE_GEN5 = "Turnip Adreno Driver T26 (@Mr_Purple_666)"
    const val WRAPPER_ADRENO_8ELITE = "Turnip Gen8 V30"
    const val WRAPPER_ADRENO_A12 = "Turnip v26.1.0 A12 Fix"

    val wrapperDriverDefaults: List<String> =
        listOf(WRAPPER_TURNIP_CAPABLE, WRAPPER_ADRENO_8ELITE_GEN5, WRAPPER_ADRENO_8ELITE, WRAPPER_ADRENO_A12)

    fun setContainerDefaults(context: Context) {
        // An x86_64 device runs an x86_64 Wine directly (X86_64GuestLibs); an
        // arm64ec build is ARM code and cannot run there, and none of the Adreno
        // or Wrapper drivers below exist for x86. Software Vulkan is the one
        // Vulkan driver every x86_64 device can load (X86_64Graphics).
        // The Wine is Proton 10 (upstream's arm64 default is Proton 10 too):
        // GameNative's x86_64 Android builds fix Wine's address space at 39
        // bits for box64 (proton-wine android/patches/x86_64/
        // dlls_ntdll_unix_virtual_c.patch), and Proton 9's ntdll then sizes its
        // page table from that fixed limit and stops on an assertion at the
        // first DLL above it on a 47-bit x86_64 kernel. From Wine 10 ntdll
        // sizes it from the host's real limit (get_host_addr_space_limit).
        if (X86_64GuestLibs.isX86_64Host()) {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-10.0-4-x86_64-1"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = X86_64Graphics.LAVAPIPE
            DefaultVersion.DXVK = "2.6.1-gplasync"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.WRAPPER = "System"
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_NORMAL
            DefaultVersion.ASYNC_CACHE = "0"
            return
        }
        // Override default driver and DXVK version based on Turnip capability
        if (GPUInformation.isTurnipCapable(context)) {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-10.0-arm64ec-2"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = "Wrapper"
            DefaultVersion.DXVK = if (GPUInformation.isAdreno6xx(context)) "1.11.1-sarek" else "2.4.1-gplasync"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.WRAPPER = WRAPPER_TURNIP_CAPABLE
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_NORMAL
            DefaultVersion.ASYNC_CACHE = "1"
        } else if (GPUInformation.isAdrenoA12(context)) {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-10.0-arm64ec-2"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = "Wrapper"
            DefaultVersion.DXVK = "2.4.1-gplasync"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.WRAPPER = WRAPPER_ADRENO_A12
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_NORMAL
            DefaultVersion.ASYNC_CACHE = "1"
        } else if (GPUInformation.isAdreno8EliteGen5(context)) {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-10.0-arm64ec-2"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = "Wrapper"
            DefaultVersion.DXVK = "2.4.1-gplasync"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.WRAPPER = WRAPPER_ADRENO_8ELITE_GEN5
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_NORMAL
            DefaultVersion.ASYNC_CACHE = "1"
        } else if (GPUInformation.isAdreno8Elite(context)) {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-10.0-arm64ec-2"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = "Wrapper"
            DefaultVersion.DXVK = "2.4.1-gplasync"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.WRAPPER = WRAPPER_ADRENO_8ELITE
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_NORMAL
            DefaultVersion.ASYNC_CACHE = "1"
        } else {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-10.0-arm64ec-2"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER =
                if (GPUInformation.isAdrenoGPU(context)) "Wrapper" else "Wrapper-gamenative"
            DefaultVersion.DXVK = "async-1.10.3"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_LIGHT
            DefaultVersion.ASYNC_CACHE = "0"
        }
    }

    /**
     * This device's recommended container, from [setContainerDefaults], for
     * an embedder that creates containers without this app's own startup.
     *
     * Read from [DefaultVersion] itself rather than from [ContainerData]'s or
     * [Container]'s defaults: those are `static final` copies taken when the
     * class loads, which can be before [setContainerDefaults] runs, and then
     * still say glibc, the main Wine and Vortek. The emulator follows the Wine
     * build the way the container dialog couples them: FEXCore for an arm64ec
     * build, Box64 for an x86_64 one.
     */
    fun deviceDefaultContainerData(context: Context): ContainerData {
        setContainerDefaults(context)
        val graphicsDriverConfig = KeyValueSet(Container.DEFAULT_GRAPHICSDRIVERCONFIG).apply {
            put("version", DefaultVersion.WRAPPER)
        }
        val dxwrapperConfig = KeyValueSet(Container.DEFAULT_DXWRAPPERCONFIG).apply {
            put("version", DefaultVersion.DXVK)
            put("vkd3dVersion", DefaultVersion.VKD3D)
            put("async", DefaultVersion.ASYNC)
            put("asyncCache", DefaultVersion.ASYNC_CACHE)
        }
        val arm64ec = DefaultVersion.WINE_VERSION.contains("arm64ec", ignoreCase = true)
        return ContainerData(
            containerVariant = DefaultVersion.VARIANT,
            wineVersion = DefaultVersion.WINE_VERSION,
            graphicsDriver = com.winlator.core.StringUtils.parseIdentifier(DefaultVersion.DEFAULT_GRAPHICS_DRIVER),
            graphicsDriverConfig = graphicsDriverConfig.toString(),
            dxwrapper = Container.DEFAULT_DXWRAPPER,
            dxwrapperConfig = dxwrapperConfig.toString(),
            emulator = if (arm64ec) "FEXCore" else "Box64",
            box64Version = DefaultVersion.BOX64,
            fexcoreVersion = DefaultVersion.FEXCORE,
            steamType = DefaultVersion.STEAM_TYPE,
        )
    }

    fun getGPUCards(context: Context): Map<Int, GpuInfo> {
        val gpuNames = JSONArray(FileUtils.readString(context, "gpu_cards.json"))
        return List(gpuNames.length()) {
            val deviceId = gpuNames.getJSONObject(it).getInt("deviceID")
            Pair(
                deviceId,
                GpuInfo(
                    deviceId = deviceId,
                    vendorId = gpuNames.getJSONObject(it).getInt("vendorID"),
                    name = gpuNames.getJSONObject(it).getString("name"),
                ),
            )
        }.toMap()
    }

    fun toContainerData(container: Container): ContainerData {
        val renderer: String
        val csmt: Boolean
        val videoPciDeviceID: Int
        val offScreenRenderingMode: String
        val strictShaderMath: Boolean
        val videoMemorySize: String
        val mouseWarpOverride: String

        val userRegFile = File(container.rootDir, ".wine/user.reg")
        WineRegistryEditor(userRegFile).use { registryEditor ->
            renderer =
                registryEditor.getStringValue("Software\\Wine\\Direct3D", "renderer", PrefManager.renderer)
            csmt =
                registryEditor.getDwordValue("Software\\Wine\\Direct3D", "csmt", if (PrefManager.csmt) 3 else 0) != 0

            videoPciDeviceID =
                registryEditor.getDwordValue("Software\\Wine\\Direct3D", "VideoPciDeviceID", PrefManager.videoPciDeviceID)

            offScreenRenderingMode =
                registryEditor.getStringValue("Software\\Wine\\Direct3D", "OffScreenRenderingMode", PrefManager.offScreenRenderingMode)

            val strictShader = if (PrefManager.strictShaderMath) 1 else 0
            strictShaderMath =
                registryEditor.getDwordValue("Software\\Wine\\Direct3D", "strict_shader_math", strictShader) != 0

            videoMemorySize =
                registryEditor.getStringValue("Software\\Wine\\Direct3D", "VideoMemorySize", PrefManager.videoMemorySize)

            mouseWarpOverride =
                registryEditor.getStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", PrefManager.mouseWarpOverride)
        }

        // Read controller API settings from container
        val apiOrdinal = container.getInputType()
        val enableX = apiOrdinal == PreferredInputApi.XINPUT.ordinal || apiOrdinal == PreferredInputApi.BOTH.ordinal
        val enableD = apiOrdinal == PreferredInputApi.DINPUT.ordinal || apiOrdinal == PreferredInputApi.BOTH.ordinal
        val mapperType = container.getDinputMapperType()
        val useSteamInput = container.getExtra("useSteamInput", "false").toBoolean()
        // Read disable-mouse flag from container
        val disableMouse = container.isDisableMouseInput()
        // Read touchscreen-mode flag from container
        val touchscreenMode = container.isTouchscreenMode()
        // Read shooter-mode flag from container
        val shooterMode = container.isShooterMode()
        // Read gesture configuration JSON
        val gestureConfig = container.getGestureConfig()
        // Read shooter mode configuration JSON
        val shooterConfig = container.getShooterConfig()
        val externalDisplayMode = container.getExternalDisplayMode()
        val externalDisplaySwap = container.isExternalDisplaySwap()

        return ContainerData(
            name = container.name,
            screenSize = container.screenSize,
            envVars = container.envVars,
            graphicsDriver = container.graphicsDriver,
            graphicsDriverVersion = container.graphicsDriverVersion,
            graphicsDriverConfig = container.graphicsDriverConfig,
            rendererPresentMode = container.rendererPresentMode,
            displayRenderer = container.displayRenderer,
            xrRefreshRate = container.xrRefreshRate,
            sfCompatMode = container.sfCompatMode,
            dxwrapper = container.dxWrapper,
            dxwrapperConfig = container.dxWrapperConfig,
            audioDriver = container.audioDriver,
            pulseaudioLowLatency = container.getPulseaudioLowLatency(),
            wincomponents = container.winComponents,
            drives = container.drives,
            execArgs = container.execArgs,
            executablePath = container.executablePath,
            showFPS = false,
            launchRealSteam = container.isLaunchRealSteam,
            launchBionicSteam = container.isLaunchBionicSteam,
            allowSteamUpdates = container.isAllowSteamUpdates,
            steamType = container.getSteamType(),
            cpuList = container.cpuList,
            cpuListWoW64 = container.cpuListWoW64,
            wow64Mode = container.isWoW64Mode,
            startupSelection = container.startupSelection.toByte(),
            box86Version = container.box86Version,
            box64Version = container.box64Version,
            box86Preset = container.box86Preset,
            box64Preset = container.box64Preset,
            desktopTheme = container.desktopTheme,
            containerVariant = container.containerVariant,
            wineVersion = container.wineVersion,
            emulator = container.emulator,
            fexcoreVersion = container.fexCoreVersion,
            fexcorePreset = container.getFEXCorePreset(),
            language = container.language,
            sdlControllerAPI = container.isSdlControllerAPI,
            fasterExternalLoading = container.isFasterExternalLoading,
            disableLibredirect = container.isDisableLibredirect,
            useSteamInput = useSteamInput,
            forceDlc = container.isForceDlc,
            localSavesOnly = container.isLocalSavesOnly,
            steamOfflineMode = container.isSteamOfflineMode(),
            epicOfflineMode = container.isEpicOfflineMode(),
            useLegacyDRM = container.isUseLegacyDRM(),
            unpackFiles = container.isUnpackFiles(),
            suspendPolicy = container.suspendPolicy,
            portraitMode = container.isPortraitMode,
            enableXInput = enableX,
            enableDInput = enableD,
            dinputMapperType = mapperType,
            disableMouseInput = disableMouse,
            touchscreenMode = touchscreenMode,
            shooterMode = shooterMode,
            gestureConfig = gestureConfig,
            shooterConfig = shooterConfig,
            externalDisplayMode = externalDisplayMode,
            externalDisplaySwap = externalDisplaySwap,
            csmt = csmt,
            videoPciDeviceID = videoPciDeviceID,
            offScreenRenderingMode = offScreenRenderingMode,
            strictShaderMath = strictShaderMath,
            useDRI3 = container.isUseDRI3(),
            videoMemorySize = videoMemorySize,
            mouseWarpOverride = mouseWarpOverride,
            sharpnessEffect = container.getExtra("sharpnessEffect", "None"),
            sharpnessLevel = container.getExtra("sharpnessLevel", "100").toIntOrNull() ?: 100,
            sharpnessDenoise = container.getExtra("sharpnessDenoise", "100").toIntOrNull() ?: 100,
            // LSFG Vulkan frame generation
            lsfgEnabled = container.getExtra(LSFG_ENABLED_EXTRA, "false").toBoolean(),
        )
    }

    fun applyToContainer(context: Context, container: Container, containerData: ContainerData) {
        applyToContainer(context, container, containerData, saveToDisk = true)
    }

    fun applyToContainer(context: Context, container: Container, containerData: ContainerData, saveToDisk: Boolean) {
        Timber.d("Applying containerData to container. execArgs: '${containerData.execArgs}', saveToDisk: $saveToDisk")
        // Detect language change before mutating container
        val previousLanguage: String = try {
            container.language
        } catch (e: Exception) {
            container.getExtra("language", "english")
        }
        val previousForceDlc: Boolean = container.isForceDlc
        val previousSteamOfflineMode: Boolean = container.isSteamOfflineMode()

        val previousUnpackFiles: Boolean = container.isUnpackFiles
        val previousLaunchBionicSteam: Boolean = container.isLaunchBionicSteam
        val previousLaunchRealSteam: Boolean = container.isLaunchRealSteam
        val userRegFile = File(container.rootDir, ".wine/user.reg")
        WineRegistryEditor(userRegFile).use { registryEditor ->
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "renderer", containerData.renderer)
            registryEditor.setDwordValue("Software\\Wine\\Direct3D", "csmt", if (containerData.csmt) 3 else 0)
            registryEditor.setDwordValue("Software\\Wine\\Direct3D", "VideoPciDeviceID", containerData.videoPciDeviceID)
            registryEditor.setDwordValue(
                "Software\\Wine\\Direct3D",
                "VideoPciVendorID",
                getGPUCards(context)[containerData.videoPciDeviceID]!!.vendorId,
            )
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "OffScreenRenderingMode", containerData.offScreenRenderingMode)
            registryEditor.setDwordValue("Software\\Wine\\Direct3D", "strict_shader_math", if (containerData.strictShaderMath) 1 else 0)
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "VideoMemorySize", containerData.videoMemorySize)
            registryEditor.setStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", containerData.mouseWarpOverride)
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "shader_backend", "glsl")
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "UseGLSL", "enabled")
        }

        container.name = containerData.name
        container.screenSize = containerData.screenSize
        container.envVars = containerData.envVars
        container.graphicsDriver = containerData.graphicsDriver
        // Save driver config through to container
        container.graphicsDriverConfig = containerData.graphicsDriverConfig
        container.rendererPresentMode = containerData.rendererPresentMode
        container.displayRenderer = containerData.displayRenderer
        container.xrRefreshRate = containerData.xrRefreshRate
        container.sfCompatMode = containerData.sfCompatMode
        container.dxWrapper = containerData.dxwrapper
        container.dxWrapperConfig = containerData.dxwrapperConfig
        container.audioDriver = containerData.audioDriver
        container.setPulseaudioLowLatency(containerData.pulseaudioLowLatency)
        container.winComponents = containerData.wincomponents
        container.drives = containerData.drives
        container.execArgs = containerData.execArgs
        if (container.executablePath != containerData.executablePath && container.executablePath != "") {
            container.setNeedsUnpacking(true)
        }
        container.executablePath = containerData.executablePath
        container.isShowFPS = false
        container.isLaunchRealSteam = containerData.launchRealSteam
        container.isLaunchBionicSteam = containerData.launchBionicSteam
        if (previousLaunchBionicSteam != containerData.launchBionicSteam ||
            previousLaunchRealSteam != containerData.launchRealSteam) {
            container.setNeedsUnpacking(true)
        }
        container.isAllowSteamUpdates = containerData.allowSteamUpdates
        container.setSteamType(containerData.steamType)
        container.cpuList = containerData.cpuList
        container.cpuListWoW64 = containerData.cpuListWoW64
        container.isWoW64Mode = containerData.wow64Mode
        container.startupSelection = containerData.startupSelection
        container.box86Version = containerData.box86Version
        container.box64Version = containerData.box64Version
        container.box86Preset = containerData.box86Preset
        container.box64Preset = containerData.box64Preset
        container.isSdlControllerAPI = containerData.sdlControllerAPI
        container.isFasterExternalLoading = containerData.fasterExternalLoading
        container.isDisableLibredirect = containerData.disableLibredirect
        container.putExtra("useSteamInput", containerData.useSteamInput)
        container.desktopTheme = containerData.desktopTheme
        container.graphicsDriverVersion = containerData.graphicsDriverVersion
        container.containerVariant = containerData.containerVariant
        container.wineVersion = containerData.wineVersion
        container.emulator = containerData.emulator
        container.fexCoreVersion = containerData.fexcoreVersion
        container.setFEXCorePreset(containerData.fexcorePreset)
        container.setDisableMouseInput(containerData.disableMouseInput)
        container.setTouchscreenMode(containerData.touchscreenMode)
        container.setShooterMode(containerData.shooterMode)
        container.setGestureConfig(containerData.gestureConfig)
        container.setShooterConfig(containerData.shooterConfig)
        container.setExternalDisplayMode(containerData.externalDisplayMode)
        container.setExternalDisplaySwap(containerData.externalDisplaySwap)
        container.setForceDlc(containerData.forceDlc)
        container.setLocalSavesOnly(containerData.localSavesOnly)
        container.setSteamOfflineMode(containerData.steamOfflineMode)
        container.setEpicOfflineMode(containerData.epicOfflineMode)
        container.setUseLegacyDRM(containerData.useLegacyDRM)
        container.setUnpackFiles(containerData.unpackFiles)
        container.setSuspendPolicy(containerData.suspendPolicy)
        container.setPortraitMode(containerData.portraitMode)
        if (previousUnpackFiles != containerData.unpackFiles && containerData.unpackFiles) {
            container.setNeedsUnpacking(true)
        }
        container.putExtra("sharpnessEffect", containerData.sharpnessEffect)
        container.putExtra("sharpnessLevel", containerData.sharpnessLevel.toString())
        container.putExtra("sharpnessDenoise", containerData.sharpnessDenoise.toString())
        // LSFG Vulkan frame generation
        container.putExtra(LSFG_ENABLED_EXTRA, containerData.lsfgEnabled.toString())
        try {
            container.language = containerData.language
        } catch (e: Exception) {
            container.putExtra("language", containerData.language)
        }
        // Set container LC_ALL according to selected language
        val lcAll = mapLanguageToLocale(containerData.language)
        container.setLC_ALL(lcAll)

        // Apply controller settings to container
        val api = when {
            containerData.enableXInput && containerData.enableDInput -> PreferredInputApi.BOTH
            containerData.enableXInput -> PreferredInputApi.XINPUT
            containerData.enableDInput -> PreferredInputApi.DINPUT
            else -> PreferredInputApi.AUTO
        }
        container.setInputType(api.ordinal)
        container.setDinputMapperType(containerData.dinputMapperType)
        container.setUseDRI3(containerData.useDRI3)
        Timber.d("Container set: preferredInputApi=%s, dinputMapperType=0x%02x", api, containerData.dinputMapperType)

        if (saveToDisk) {
            // Mark that config has been changed, so we can show feedback dialog after next game run
            container.putExtra("config_changed", "true")
            container.saveData()
        }
        Timber.d("Set container.execArgs to '${containerData.execArgs}'")
    }

    private fun mapLanguageToLocale(language: String): String {
        return when (language.lowercase()) {
            "arabic" -> "ar_SA.utf8"
            "bulgarian" -> "bg_BG.utf8"
            "schinese" -> "zh_CN.utf8"
            "tchinese" -> "zh_TW.utf8"
            "czech" -> "cs_CZ.utf8"
            "danish" -> "da_DK.utf8"
            "dutch" -> "nl_NL.utf8"
            "english" -> "en_US.utf8"
            "finnish" -> "fi_FI.utf8"
            "french" -> "fr_FR.utf8"
            "german" -> "de_DE.utf8"
            "greek" -> "el_GR.utf8"
            "hungarian" -> "hu_HU.utf8"
            "italian" -> "it_IT.utf8"
            "japanese" -> "ja_JP.utf8"
            "koreana" -> "ko_KR.utf8"
            "norwegian" -> "nb_NO.utf8"
            "polish" -> "pl_PL.utf8"
            "portuguese" -> "pt_PT.utf8"
            "brazilian" -> "pt_BR.utf8"
            "romanian" -> "ro_RO.utf8"
            "russian" -> "ru_RU.utf8"
            "spanish" -> "es_ES.utf8"
            "latam" -> "es_MX.utf8"
            "swedish" -> "sv_SE.utf8"
            "thai" -> "th_TH.utf8"
            "turkish" -> "tr_TR.utf8"
            "ukrainian" -> "uk_UA.utf8"
            "vietnamese" -> "vi_VN.utf8"
            else -> "en_US.utf8"
        }
    }

    /**
     * Extracts the game ID from a container ID string
     * Handles formats like:
     * - STEAM_123456 -> 123456
     * - EPIC_2938123
     * - CUSTOM_GAME_571969840 -> 571969840
     * - GOG_19283103 -> 19283103
     * - STEAM_123456(1) -> 123456
     * - 19283103 -> 19283103 (legacy GOG format)
     */
    fun extractGameIdFromContainerId(containerId: String): Int {
        // Remove duplicate suffix like (1), (2) if present
        val idWithoutSuffix = if (containerId.contains("(")) {
            containerId.substringBefore("(")
        } else {
            containerId
        }

        // Split by underscores and find the last numeric part
        val parts = idWithoutSuffix.split("_")
        // The last part should be the numeric ID
        val lastPart = parts.lastOrNull() ?: throw IllegalArgumentException("Invalid container ID format: $containerId")

        return try {
            lastPart.toInt()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Could not extract game ID from container ID: $containerId", e)
        }
    }
}
