package dev.droidtop.runtime.windows.ui.screen.xserver

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import dev.droidtop.runtime.windows.ui.data.XServerState
import dev.droidtop.runtime.windows.utils.AssetUtils
import dev.droidtop.runtime.windows.utils.downloader.CoreDriverDownloader
import dev.droidtop.runtime.windows.utils.ManifestComponentHelper
import dev.droidtop.runtime.windows.utils.downloader.DXWrapperDownloader
import dev.droidtop.runtime.windows.utils.downloader.GraphicsDriverDownloader
import dev.droidtop.runtime.windows.utils.downloader.WinComponentDownloader
import dev.droidtop.runtime.windows.utils.X86_64GuestLibs
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.AppUtils
import com.winlator.core.DXVKHelper
import com.winlator.core.DefaultVersion
import com.winlator.core.FileUtils
import com.winlator.core.GPUHelper
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import com.winlator.core.OnExtractFileListener
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineInfo
import com.winlator.core.WineRegistryEditor
import com.winlator.core.WineStartMenuCreator
import com.winlator.core.WineThemeManager
import com.winlator.core.WineUtils
import com.winlator.core.envvars.EnvVars
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.ImageFs
import com.winlator.xserver.ScreenInfo
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.Arrays
import java.util.Locale
import kotlin.text.lowercase
import com.winlator.PrefManager as WinlatorPrefManager

/*
 * What has to be true of a Wine prefix before a guest starts in it: drive
 * links, Wine's own system files, the DX wrapper and graphics driver files,
 * input DLLs, the Wine audio driver. The functions GameNative keeps at the end
 * of its XServerScreen.kt (app/gamenative/ui/screen/xserver, GPL-3.0), moved
 * here unchanged apart from the in-prefix Steam client, which droidtop does not
 * carry. droidtop's WinePrefixPreparation calls them in GameNative's order.
 */

private const val ALWAYS_REEXTRACT = true

internal fun extractArm64ecInputDLLs(context: Context, container: Container) {
    val inputAsset = "arm64ec_input_dlls.tzst"
    val imageFs = ImageFs.find(context)
    val wineVersion: String? = container.getWineVersion()
    Log.d("XServerDisplayActivity", "arm64ec Input DLL Extraction Verification: Container Wine version: " + wineVersion)

    // Check if the wineVersion string is not null and contains "arm64ec"
    if (wineVersion != null && wineVersion.contains("proton-9.0-arm64ec")) {
        val wineFolder: File = File(imageFs.getWinePath() + "/lib/wine/")
        Log.d("XServerDisplayActivity", "Wine version contains arm64ec. Extracting input dlls to " + wineFolder.getPath())
        val success: Boolean = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, inputAsset, wineFolder)
        if (!success) {
            Log.d("XServerDisplayActivity", "Failed to extract input dlls")
        }
    } else {
        // Updated log message for clarity
        Log.d("XServerDisplayActivity", "Wine version is not arm64ec, skipping input dlls extraction.")
    }
}

internal fun extractx86_64InputDlls(context: Context, container: Container) {
    val inputAsset = "x86_64_input_dlls.tzst"
    val imageFs = ImageFs.find(context)
    val wineVersion: String? = container.getWineVersion()
    Log.d("XServerDisplayActivity", "x86_64 Input DLL Extraction Verification: Container Wine version: " + wineVersion)
    if ("proton-9.0-x86_64" == wineVersion) {
        val wineFolder: File = File(imageFs.getWinePath() + "/lib/wine/")
        Log.d("XServerDisplayActivity", "Extracting input dlls to " + wineFolder.getPath())
    } else Log.d("XServerDisplayActivity", "Wine version is not proton-9.0-x86_64, skipping input dlls extraction")
}

internal suspend fun setupWineSystemFiles(
    context: Context,
    firstTimeBoot: Boolean,
    screenInfo: ScreenInfo,
    xServerState: MutableState<XServerState>,
    // xServerViewModel: XServerViewModel,
    container: Container,
    containerManager: ContainerManager,
    // shortcut: Shortcut?,
    envVars: EnvVars,
    contentsManager: ContentsManager,
    onExtractFileListener: OnExtractFileListener?,
) {
    val imageFs = ImageFs.find(context)
    val appVersion = AppUtils.getVersionCode(context).toString()
    val imgVersion = imageFs.getVersion().toString()
    var containerDataChanged = false

    val appliedContainerVariant = container.getExtra("appliedContainerVariant")
    val appliedWineVersion = container.getExtra("appliedWineVersion")
    val markersMissing = appliedContainerVariant.isEmpty() || appliedWineVersion.isEmpty()
    val firstBoot = container.getExtra("appVersion").isEmpty()
    val imgVersionChanged = container.getExtra("imgVersion") != imgVersion
    val variantChanged = !markersMissing && container.containerVariant != appliedContainerVariant
    val wineVersionChanged = !markersMissing && container.wineVersion != appliedWineVersion

    if (firstBoot || imgVersionChanged || variantChanged || wineVersionChanged) {
        applyGeneralPatches(context, container, imageFs, xServerState.value.wineInfo, containerManager, onExtractFileListener)
        container.putExtra("appliedContainerVariant", container.containerVariant)
        container.putExtra("appliedWineVersion", container.wineVersion)
        container.putExtra("appVersion", appVersion)
        container.putExtra("imgVersion", imgVersion)
        containerDataChanged = true
    } else if (markersMissing) {
        // Pre-existing container: trust the on-disk prefix and adopt it as-is.
        container.putExtra("appliedContainerVariant", container.containerVariant)
        container.putExtra("appliedWineVersion", container.wineVersion)
        containerDataChanged = true
    }

    // Always refresh components files
    refreshComponentsFiles(context)

    // Normalize dxwrapper for state (dxvk includes version for extraction switch)
    if (xServerState.value.dxwrapper == "dxvk") {
        xServerState.value = xServerState.value.copy(
            dxwrapper = "dxvk-" + xServerState.value.dxwrapperConfig?.get("version"),
        )
    }

    // Also normalize VKD3D to include version like vkd3d-<version>
    if (xServerState.value.dxwrapper == "vkd3d") {
        xServerState.value = xServerState.value.copy(
            dxwrapper = "vkd3d-" + xServerState.value.dxwrapperConfig?.get("vkd3dVersion"),
        )
    }

    val needReextract = ALWAYS_REEXTRACT || xServerState.value.dxwrapper != container.getExtra("dxwrapper") || variantChanged || wineVersionChanged

    Timber.i("needReextract is " + needReextract)
    Timber.i("xServerState.value.dxwrapper is " + xServerState.value.dxwrapper)
    Timber.i("container.getExtra(\"dxwrapper\") is " + container.getExtra("dxwrapper"))

    if (needReextract) {
        extractDXWrapperFiles(
            context,
            firstTimeBoot,
            container,
            containerManager,
            xServerState.value.dxwrapper,
            imageFs,
            contentsManager,
            onExtractFileListener,
        )
        container.putExtra("dxwrapper", xServerState.value.dxwrapper)
        containerDataChanged = true
    }

    if (xServerState.value.dxwrapper == "cnc-ddraw") envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini")

    // val wincomponents = if (shortcut != null) shortcut.getExtra("wincomponents", container.winComponents) else container.winComponents
    val wincomponents = container.winComponents
    if (!wincomponents.equals(container.getExtra("wincomponents"))) {
        extractWinComponentFiles(context, firstTimeBoot, imageFs, container, containerManager, onExtractFileListener)
        container.putExtra("wincomponents", wincomponents)
        containerDataChanged = true
    }

    // OpenAL audio: extract native DLLs if WINEDLLOVERRIDES mentions openal32 or soft_oal
    val dllOverrides = EnvVars(container.envVars).get("WINEDLLOVERRIDES")
    val needsOpenalDlls = dllOverrides.contains("openal32") || dllOverrides.contains("soft_oal")
    val openalState = if (needsOpenalDlls) "yes" else "no"
    if (openalState != container.getExtra("openal_dlls") || firstTimeBoot) {
        if (needsOpenalDlls) {
            val windowsDir = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")

            // Download or use cached/bundled openal component
            val openalFile = WinComponentDownloader.ensureWinComponentAvailable(context, "openal") { progress ->
                Timber.d("Downloading openal component: ${(progress * 100).toInt()}%")
            }

            if (openalFile == null) {
                // Legacy variant: use bundled asset
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, context.assets,
                    "wincomponents/openal.tzst", windowsDir, onExtractFileListener,
                )
            } else {
                // Modern variant: use downloaded file
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, openalFile,
                    windowsDir, onExtractFileListener,
                )
            }
        }
        container.putExtra("openal_dlls", openalState)
        containerDataChanged = true
    }

    // droidtop: GameNative's in-prefix Steam client (extractSteamFiles) is not
    // carried; droidtop's Steam is its own store.

    // If bionic mode is off, scrub any bionic-installed files from a previous
    // enable. The Wine-side lsteamclient.dll comes from Proton's own tree in
    // non-bionic launches, and the native libsteamclient.so should not be
    // present at all unless bionic is on.
    if (!container.isLaunchBionicSteam) {
        cleanupBionicSteamAssets(imageFs)
    }

    val desktopTheme = container.desktopTheme
    if ((desktopTheme + "," + screenInfo) != container.getExtra("desktopTheme")) {
        WineThemeManager.apply(context, WineThemeManager.ThemeInfo(desktopTheme), screenInfo)
        container.putExtra("desktopTheme", desktopTheme + "," + screenInfo)
        containerDataChanged = true
    }

    WineStartMenuCreator.create(context, container)
    WineUtils.createDosdevicesSymlinks(context, container)

    // The EOS overlay's CEF browser needs RpcSs and BITS: without them it
    // crash-loops and EOS login fails. Force normal services only for containers
    // that actually have the overlay installed.
    // droidtop: the Epic overlay (EpicOverlayManager) is not carried, so no
    // container needs the overlay's services forced on.
    val effectiveStartupSelection = container.startupSelection
    val startupSelection = effectiveStartupSelection.toString()
    if (startupSelection != container.getExtra("startupSelection")) {
        WineUtils.changeServicesStatus(container, effectiveStartupSelection != Container.STARTUP_SELECTION_NORMAL)
        container.putExtra("startupSelection", startupSelection)
        containerDataChanged = true
    }

    if (containerDataChanged) container.saveData()
}

private suspend fun applyGeneralPatches(
    context: Context,
    container: Container,
    imageFs: ImageFs,
    wineInfo: WineInfo,
    containerManager: ContainerManager,
    onExtractFileListener: OnExtractFileListener?,
) {
    Timber.i("Applying general patches")
    val rootDir = imageFs.getRootDir()
    val contentsManager = ContentsManager(context)
    if (container.containerVariant.equals(Container.GLIBC)) {
        FileUtils.delete(File(rootDir, "/opt/apps"))
        val downloaded = File(imageFs.getFilesDir(), "imagefs_patches_gamenative.tzst")
        Timber.i("Extracting imagefs_patches_gamenative.tzst")
        if (Arrays.asList<String?>(*context.getAssets().list("")).contains("imagefs_patches_gamenative.tzst") == true) {
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context.assets,
                "imagefs_patches_gamenative.tzst",
                rootDir,
                onExtractFileListener,
            )
        } else if (downloaded.exists()){
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                downloaded,
                rootDir,
                onExtractFileListener,
            );
        }
        Timber.i("Extracting WFM from container_pattern_common.tzst")
        check(containerManager.extractContainerPatternCommonWfm(rootDir, onExtractFileListener)) {
            "Failed to extract WFM from container_pattern_common.tzst"
        }
    } else {
        Timber.i("Extracting container_pattern_common.tzst")
        containerManager.extractContainerPatternCommon(rootDir, onExtractFileListener)
        Timber.i("Attempting to extract _container_pattern.tzst with wine version " + container.wineVersion)
    }
    containerManager.extractContainerPatternFile(container.wineVersion, contentsManager, container.rootDir, onExtractFileListener)
    WineUtils.applySystemTweaks(context, wineInfo)
    container.putExtra("graphicsDriver", null)
    container.putExtra("desktopTheme", null)
    container.putExtra("xaudioDllsExtracted", null)
    container.putExtra("wincomponents", null)
    container.putExtra("audioDriver", null)
    container.putExtra("startupSelection", null)
    WinlatorPrefManager.init(context)
    WinlatorPrefManager.putString("current_box64_version", "")
}

private fun refreshComponentsFiles(context: Context) {
    // The modules and pactl must match the CPU the daemon runs on. An app
    // that ships an x86_64 set packs it as pulseaudio-gamenative-x86_64.tzst
    // (droidtop builds one); without it the aarch64 set is all there is.
    val x86_64Pulse = "pulseaudio-gamenative-x86_64.tzst"
    val pulseAsset = if (X86_64GuestLibs.isX86_64Host() && context.assets.list("")?.contains(x86_64Pulse) == true) {
        x86_64Pulse
    } else {
        "pulseaudio-gamenative-20260612.tzst"
    }
    val extractionPairs = listOf(
        pulseAsset to File(context.filesDir, "pulseaudio")
    )

    AssetUtils.extractComponentsWithVersionCheck(
        extractionPairs,
        context.assets,
        TarCompressorUtils.Type.ZSTD
    )
}

/**
 * Helper function to extract a graphics driver component, downloading if needed (modern variant)
 * or using bundled assets (legacy variant).
 */
private suspend fun extractGraphicsDriverComponent(
    context: Context,
    componentId: String,
    rootDir: File,
    onExtractFileListener: OnExtractFileListener? = null
) {
    val componentFile = GraphicsDriverDownloader.ensureGraphicsDriverAvailable(context, componentId) { progress ->
        Timber.d("Downloading graphics driver $componentId: ${(progress * 100).toInt()}%")
    }

    if (componentFile == null) {
        // Legacy variant: use bundled asset
        Timber.d("Extracting graphics driver $componentId from bundled assets")
        TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, context.assets,
            "graphics_driver/$componentId.tzst", rootDir, onExtractFileListener,
        )
    } else {
        // Modern variant: use downloaded file
        Timber.d("Extracting graphics driver $componentId from downloaded file: ${componentFile.absolutePath}")
        val extractType = if (componentFile.name.endsWith(".tar.xz")) {
            TarCompressorUtils.Type.XZ
        } else {
            TarCompressorUtils.Type.ZSTD
        }
        TarCompressorUtils.extract(
            extractType, componentFile,
            rootDir, onExtractFileListener,
        )
    }
}

/**
 * Helper function to extract a dxwrapper component, downloading if needed (modern variant)
 * or using bundled assets (legacy variant).
 */
private suspend fun extractDXWrapperComponent(
    context: Context,
    componentId: String,
    windowsDir: File,
    onExtractFileListener: OnExtractFileListener?
) {
    val componentFile = DXWrapperDownloader.ensureDXWrapperAvailable(context, componentId) { progress ->
        Timber.d("Downloading dxwrapper $componentId: ${(progress * 100).toInt()}%")
    }

    if (componentFile == null) {
        // Legacy variant: use bundled asset
        Timber.d("Extracting dxwrapper $componentId from bundled assets")
        TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, context.assets,
            "dxwrapper/$componentId.tzst", windowsDir, onExtractFileListener,
        )
    } else {
        // Modern variant: use downloaded file
        Timber.d("Extracting dxwrapper $componentId from downloaded file: ${componentFile.absolutePath}")
        TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, componentFile,
            windowsDir, onExtractFileListener,
        )
    }
}

private suspend fun extractDXWrapperFiles(
    context: Context,
    firstTimeBoot: Boolean,
    container: Container,
    containerManager: ContainerManager,
    dxwrapper: String,
    imageFs: ImageFs,
    contentsManager: ContentsManager,
    onExtractFileListener: OnExtractFileListener?,
) {
    val dlls = arrayOf(
        "d3d10.dll",
        "d3d10_1.dll",
        "d3d10core.dll",
        "d3d11.dll",
        "d3d12.dll",
        "d3d12core.dll",
        "d3d8.dll",
        "d3d9.dll",
        "dxgi.dll",
        "ddraw.dll",
    )
    val splitDxWrapper = dxwrapper.split("-")[0]
    if (firstTimeBoot && splitDxWrapper != "vkd3d") cloneOriginalDllFiles(imageFs, *dlls)
    val rootDir = imageFs.getRootDir()
    val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")

    when (splitDxWrapper) {
        "wined3d" -> {
            restoreOriginalDllFiles(context, container, containerManager, imageFs, *dlls)
        }
        "cnc-ddraw" -> {
            restoreOriginalDllFiles(context, container, containerManager, imageFs, *dlls)
            val assetDir = "dxwrapper/cnc-ddraw-" + DefaultVersion.CNC_DDRAW
            val configFile = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/ProgramData/cnc-ddraw/ddraw.ini")
            if (!configFile.isFile) FileUtils.copy(context, "$assetDir/ddraw.ini", configFile)
            val shadersDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/ProgramData/cnc-ddraw/Shaders")
            FileUtils.delete(shadersDir)
            FileUtils.copy(context, "$assetDir/Shaders", shadersDir)
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context.assets,
                "$assetDir/ddraw.tzst", windowsDir, onExtractFileListener,
            )
        }
        "vkd3d" -> {
            Timber.i("Extracting VKD3D D3D12 DLLs for dxwrapper: $dxwrapper")
            val profile: ContentProfile? = contentsManager.getProfileByEntryName(dxwrapper)
            // Determine graphics driver to choose DXVK version
            val vortekLike = container.graphicsDriver == "vortek" || container.graphicsDriver == "adreno" || container.graphicsDriver == "sd-8-elite"
            val dxvkMinVersion = "2.6.1-gplasync"
            val dxwrapperConfig = DXVKHelper.parseConfig(container.dxWrapperConfig)
            val dxvkVersion = dxwrapperConfig.get("version", dxvkMinVersion)
            val dxvkVersionForVkd3d = if (vortekLike && GPUHelper.vkGetApiVersionSafe() < GPUHelper.vkMakeVersion(1, 3, 0)) {
                "1.10.3"
            } else if (ManifestComponentHelper.isAtLeastVersion(dxvkVersion, 2, 1, 0)) {
                dxvkVersion
            } else {
                dxvkMinVersion
            }
            Timber.i("Extracting VKD3D DX version for dxwrapper: $dxvkVersionForVkd3d")
            extractDXWrapperComponent(context, "dxvk-$dxvkVersionForVkd3d", windowsDir, onExtractFileListener)

            if (profile != null) {
                Timber.d("Applying user-defined VKD3D content profile: " + dxwrapper)
                contentsManager.applyContent(profile);
            } else {
                // Determine VKD3D version from state config
                Timber.i("Extracting VKD3D D3D12 DLLs version: $dxwrapper")
                extractDXWrapperComponent(context, dxwrapper, windowsDir, onExtractFileListener)
            }
        }
        else -> {
            val profile: ContentProfile? = contentsManager.getProfileByEntryName(dxwrapper)
            // This block handles dxvk-VERSION strings
            Timber.i("Extracting DXVK/D8VK DLLs for dxwrapper: $dxwrapper")
            restoreOriginalDllFiles(context, container, containerManager, imageFs, "d3d12.dll", "d3d12core.dll", "ddraw.dll")
            if (profile != null) {
                Timber.d("Applying user-defined DXVK content profile: " + dxwrapper)
                contentsManager.applyContent(profile);
            } else {
                extractDXWrapperComponent(context, dxwrapper, windowsDir, onExtractFileListener)
            }
            extractDXWrapperComponent(context, "d8vk-${DefaultVersion.D8VK}", windowsDir, onExtractFileListener)
        }
    }
}
private fun cloneOriginalDllFiles(imageFs: ImageFs, vararg dlls: String) {
    val rootDir = imageFs.rootDir
    val cacheDir = File(rootDir, ImageFs.CACHE_PATH + "/original_dlls")
    if (!cacheDir.isDirectory) cacheDir.mkdirs()
    val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
    val dirnames = arrayOf("system32", "syswow64")

    for (dll in dlls) {
        for (dirname in dirnames) {
            val dllFile = File(windowsDir, "$dirname/$dll")
            if (dllFile.isFile) FileUtils.copy(dllFile, File(cacheDir, "$dirname/$dll"))
        }
    }
}
private fun restoreOriginalDllFiles(
    context: Context,
    container: Container,
    containerManager: ContainerManager,
    imageFs: ImageFs,
    vararg dlls: String,
) {
    val rootDir = imageFs.rootDir
    if (container.containerVariant.equals(Container.GLIBC)) {
        val cacheDir = File(rootDir, ImageFs.CACHE_PATH + "/original_dlls")
        val contentsManager = ContentsManager(context)
        if (cacheDir.isDirectory) {
            val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
            val dirnames = cacheDir.list()
            var filesCopied = 0

            for (dll in dlls) {
                var success = false
                for (dirname in dirnames!!) {
                    val srcFile = File(cacheDir, "$dirname/$dll")
                    val dstFile = File(windowsDir, "$dirname/$dll")
                    if (FileUtils.copy(srcFile, dstFile)) success = true
                }
                if (success) filesCopied++
            }

            if (filesCopied == dlls.size) return
        }

        containerManager.extractContainerPatternFile(
            container.wineVersion, contentsManager, container.rootDir,
            object : OnExtractFileListener {
                override fun onExtractFile(file: File, size: Long): File? {
                    val path = file.path
                    if (path.contains("system32/") || path.contains("syswow64/")) {
                        for (dll in dlls) {
                            if (path.endsWith("system32/$dll") || path.endsWith("syswow64/$dll")) return file
                        }
                    }
                    return null
                }
            },
        )

        cloneOriginalDllFiles(imageFs, *dlls)
    } else {
        val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
        var system32dlls: File? = null
        var syswow64dlls: File? = null

        if (container.wineVersion.contains("arm64ec")) system32dlls = File(imageFs.getWinePath() + "/lib/wine/aarch64-windows")
        else system32dlls = File(imageFs.getWinePath() + "/lib/wine/x86_64-windows")

        syswow64dlls = File(imageFs.getWinePath() + "/lib/wine/i386-windows")

        for (dll in dlls) {
            var srcFile = File(system32dlls, dll)
            var dstFile = File(windowsDir, "system32/" + dll)
            FileUtils.copy(srcFile, dstFile)
            srcFile = File(syswow64dlls, dll)
            dstFile = File(windowsDir, "syswow64/" + dll)
            FileUtils.copy(srcFile, dstFile)
        }
    }
}
private suspend fun extractWinComponentFiles(
    context: Context,
    firstTimeBoot: Boolean,
    imageFs: ImageFs,
    container: Container,
    containerManager: ContainerManager,
    // shortcut: Shortcut?,
    onExtractFileListener: OnExtractFileListener?,
) {
    val rootDir = imageFs.rootDir
    val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
    val systemRegFile = File(rootDir, ImageFs.WINEPREFIX + "/system.reg")

    try {
        val wincomponentsJSONObject = JSONObject(FileUtils.readString(context, "wincomponents/wincomponents.json"))
        val dlls = mutableListOf<String>()
        // val wincomponents = if (shortcut != null) shortcut.getExtra("wincomponents", container.winComponents) else container.winComponents
        val wincomponents = container.winComponents

        if (firstTimeBoot) {
            for (wincomponent in KeyValueSet(wincomponents)) {
                val dlnames = wincomponentsJSONObject.getJSONArray(wincomponent[0])
                for (i in 0 until dlnames.length()) {
                    val dlname = dlnames.getString(i)
                    dlls.add(if (!dlname.endsWith(".exe")) "$dlname.dll" else dlname)
                }
            }

            cloneOriginalDllFiles(imageFs, *dlls.toTypedArray())
            dlls.clear()
        }

        val oldWinComponentsMap = KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).associate { it[0] to it[1] }

        for (wincomponent in KeyValueSet(wincomponents)) {
            val oldValue = oldWinComponentsMap[wincomponent[0]]
            if (oldValue == null){

                Timber.d("Wincomponent ${wincomponent[0]} does not exist in oldwincomponents, skipping")
            }
            if (oldValue == wincomponent[1] && !firstTimeBoot) continue
            val identifier = wincomponent[0]
            val useNative = wincomponent[1].equals("1")

            if (!container.wineVersion.contains("arm64ec") && identifier.contains("opengl") && useNative) continue

            // Note: GameNative do not bundle directinput and directinput8 dlls, need to skip them and use wine/proton dll instead
            if (useNative && (identifier != "directinput8" && identifier != "directinput")) {
                // Download or use cached/bundled wincomponent
                val componentFile = WinComponentDownloader.ensureWinComponentAvailable(
                    context, identifier
                ) { progress ->
                    Timber.d("Downloading wincomponent $identifier: ${(progress * 100).toInt()}%")
                }

                if (componentFile == null) {
                    // Legacy variant: use bundled asset
                    Timber.d("Extracting wincomponent $identifier from bundled assets")
                    TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD, context.assets,
                        "wincomponents/$identifier.tzst", windowsDir, onExtractFileListener,
                    )
                } else {
                    // Modern variant: use downloaded file
                    Timber.d("Extracting wincomponent $identifier from downloaded file: ${componentFile.absolutePath}")
                    TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD, componentFile,
                        windowsDir, onExtractFileListener,
                    )
                }
            } else {
                val dlnames = wincomponentsJSONObject.getJSONArray(identifier)
                for (i in 0 until dlnames.length()) {
                    val dlname = dlnames.getString(i)
                    dlls.add(if (!dlname.endsWith(".exe")) "$dlname.dll" else dlname)
                }
            }
            WineUtils.overrideWinComponentDlls(context, container, identifier, useNative)
            WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative)
        }

        if (!dlls.isEmpty()) restoreOriginalDllFiles(context, container, containerManager, imageFs, *dlls.toTypedArray())
    } catch (e: JSONException) {
        Timber.e("Failed to read JSON: $e")
    }
}

internal suspend fun extractGraphicsDriverFiles(
    context: Context,
    graphicsDriver: String,
    dxwrapper: String,
    dxwrapperConfig: KeyValueSet,
    container: Container,
    envVars: EnvVars,
    firstTimeBoot: Boolean,
    vkbasaltConfig: String,
) {
    // Every driver below (Turnip, Adreno, Vortek, the Wrapper ICDs, vkBasalt)
    // is an aarch64 library. An x86_64 guest gets its driver from
    // X86_64Graphics when the launcher builds its environment.
    if (X86_64GuestLibs.isX86_64Host()) return
    if (container.containerVariant.equals(Container.GLIBC)) {
        // Get the configured driver version or use default
        val turnipVersion =
            container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "turnip" } ?: DefaultVersion.TURNIP
        val virglVersion = container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "virgl" } ?: DefaultVersion.VIRGL
        val zinkVersion = container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "zink" } ?: DefaultVersion.ZINK
        val adrenoVersion =
            container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "adreno" } ?: DefaultVersion.ADRENO
        val sd8EliteVersion =
            container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "sd-8-elite" } ?: DefaultVersion.SD8ELITE

        var cacheId = graphicsDriver
        if (graphicsDriver == "turnip") {
            cacheId += "-" + turnipVersion + "-" + zinkVersion
            if (GPUInformation.isAdreno710_720_732(context)) {
                val userEnvVars = EnvVars(container.envVars)
                val tuDebug = userEnvVars.get("TU_DEBUG")
                if (!tuDebug.contains("gmem")) userEnvVars.put("TU_DEBUG", (if (!tuDebug.isEmpty()) "$tuDebug," else "") + "gmem")
                container.envVars = userEnvVars.toString()
            } else if (turnipVersion == "25.2.0" || turnipVersion == "25.3.0") {
                envVars.put("TU_DEBUG", "sysmem");
            }
        } else if (graphicsDriver == "virgl") {
            cacheId += "-" + DefaultVersion.VIRGL
        } else if (graphicsDriver == "vortek" || graphicsDriver == "adreno" || graphicsDriver == "sd-8-elite") {
            cacheId += "-" + DefaultVersion.VORTEK
        }

        val imageFs = ImageFs.find(context)
        val configDir = imageFs.configDir
        val sentinel = File(configDir, ".current_graphics_driver")   // lives in shared tree
        val onDiskId = sentinel.takeIf { it.exists() }?.readText() ?: ""
        val changed = ALWAYS_REEXTRACT || cacheId != container.getExtra("graphicsDriver") || cacheId != onDiskId
        Timber.i("Changed is " + changed + " will re-extract drivers accordingly.")
        val rootDir = imageFs.rootDir
        envVars.put("vblank_mode", "0")

        if (changed) {
            FileUtils.delete(File(imageFs.lib32Dir, "libvulkan_freedreno.so"))
            FileUtils.delete(File(imageFs.lib64Dir, "libvulkan_freedreno.so"))
            FileUtils.delete(File(imageFs.lib64Dir, "libvulkan_vortek.so"))
            FileUtils.delete(File(imageFs.lib32Dir, "libvulkan_vortek.so"))
            FileUtils.delete(File(imageFs.lib32Dir, "libGL.so.1.7.0"))
            FileUtils.delete(File(imageFs.lib64Dir, "libGL.so.1.7.0"))
            val vulkanICDDir = File(rootDir, "/usr/share/vulkan/icd.d")
            FileUtils.delete(vulkanICDDir)
            vulkanICDDir.mkdirs()
            container.putExtra("graphicsDriver", cacheId)
            container.saveData()
            if (!sentinel.exists()) {
                sentinel.parentFile?.mkdirs()
                sentinel.createNewFile()
            }
            sentinel.writeText(cacheId)
        }
        if (dxwrapper.contains("dxvk")) {
            DXVKHelper.setEnvVars(context, dxwrapperConfig, envVars)
        } else if (dxwrapper.contains("vkd3d")) {
            DXVKHelper.setVKD3DEnvVars(context, dxwrapperConfig, envVars)
        }

        if (graphicsDriver == "turnip") {
            envVars.put("GALLIUM_DRIVER", "zink")
            envVars.put("TU_OVERRIDE_HEAP_SIZE", "4096")
            if (!envVars.has("MESA_VK_WSI_PRESENT_MODE")) envVars.put("MESA_VK_WSI_PRESENT_MODE", "mailbox")
            envVars.put("vblank_mode", "0")

            if (!GPUInformation.isAdreno6xx(context) && !GPUInformation.isAdreno710_720_732(context)) {
                val userEnvVars = EnvVars(container.envVars)
                val tuDebug = userEnvVars.get("TU_DEBUG")
                if (!tuDebug.contains("sysmem")) userEnvVars.put("TU_DEBUG", (if (!tuDebug.isEmpty()) "$tuDebug," else "") + "sysmem")
                container.envVars = userEnvVars.toString()
            }

            if (changed) {
                extractGraphicsDriverComponent(context, "turnip-$turnipVersion", rootDir)
                extractGraphicsDriverComponent(context, "zink-$zinkVersion", rootDir)
            }
        } else if (graphicsDriver == "virgl") {
            envVars.put("GALLIUM_DRIVER", "virpipe")
            envVars.put("VIRGL_NO_READBACK", "true")
            envVars.put("VIRGL_SERVER_PATH", imageFs.getRootDir().getPath() + UnixSocketConfig.VIRGL_SERVER_PATH)
            envVars.put("MESA_EXTENSION_OVERRIDE", "-GL_EXT_vertex_array_bgra")
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.1")
            envVars.put("vblank_mode", "0")
            if (changed) {
                extractGraphicsDriverComponent(context, "virgl-$virglVersion", rootDir)
            }
        } else if (graphicsDriver == "vortek") {
            Timber.i("Setting Vortek env vars")
            envVars.put("GALLIUM_DRIVER", "zink")
            envVars.put("ZINK_CONTEXT_THREADED", "1")
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3")
            envVars.put("WINEVKUSEPLACEDADDR", "1")
            envVars.put("VORTEK_SERVER_PATH", imageFs.getRootDir().getPath() + UnixSocketConfig.VORTEK_SERVER_PATH)
            Timber.i("dxwrapper is " + dxwrapper)
            if (dxwrapper.contains("dxvk")) {
                envVars.put("WINE_D3D_CONFIG", "renderer=gdi")
            }
            if (changed) {
                extractGraphicsDriverComponent(context, "vortek-2.1", rootDir)
                extractGraphicsDriverComponent(context, "zink-22.2.5", rootDir)
            }
        } else if (graphicsDriver == "adreno" || graphicsDriver == "sd-8-elite") {
            val assetZip = if (graphicsDriver == "adreno") "Adreno_${adrenoVersion}_adpkg.zip" else "SD8Elite_${sd8EliteVersion}.zip"

            val componentRoot = com.winlator.core.GeneralComponents.getComponentDir(
                com.winlator.core.GeneralComponents.Type.ADRENOTOOLS_DRIVER,
                context,
            )

            // Download or get cached core driver
            val driverFile = CoreDriverDownloader.ensureCoreDriverAvailable(context, assetZip) { progress ->
                Timber.d("Downloading core driver $assetZip: ${(progress * 100).toInt()}%")
            }

            // Read manifest name from zip to determine folder name
            val identifier = if (driverFile != null) {
                // Modern variant: read from downloaded file
                com.winlator.core.FileUtils.readZipManifestNameFromFile(driverFile) ?: assetZip.substringBeforeLast('.')
            } else {
                // Legacy variant: read from assets
                readZipManifestNameFromAssets(context, assetZip) ?: assetZip.substringBeforeLast('.')
            }

            // Only (re)extract if changed
            val adrenoCacheId = "${graphicsDriver}-${identifier}"
            val needsExtract = changed || adrenoCacheId != container.getExtra("graphicsDriverAdreno")

            if (needsExtract) {
                val destinationDir = File(componentRoot.toString())
                if (destinationDir.isDirectory) {
                    FileUtils.delete(destinationDir)
                }
                destinationDir.mkdirs()

                if (driverFile != null) {
                    // Modern variant: extract from downloaded file
                    Timber.d("Extracting core driver from downloaded file: ${driverFile.absolutePath}")
                    com.winlator.core.FileUtils.extractZipFromFile(driverFile, destinationDir)
                } else {
                    // Legacy variant: extract from assets
                    Timber.d("Extracting core driver from bundled assets: $assetZip")
                    com.winlator.core.FileUtils.extractZipFromAssets(context, assetZip, destinationDir)
                }

                val targetLibName = "vulkan.adreno.so"

                // Update cache and only the adrenotoolsDriver key within graphics driver config
                container.putExtra("graphicsDriverAdreno", adrenoCacheId)
                container.saveData()
            }
            envVars.put("GALLIUM_DRIVER", "zink")
            envVars.put("ZINK_CONTEXT_THREADED", "1")
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3")
            envVars.put("WINEVKUSEPLACEDADDR", "1")
            envVars.put("VORTEK_SERVER_PATH", imageFs.getRootDir().getPath() + UnixSocketConfig.VORTEK_SERVER_PATH)
            Timber.i("dxwrapper is " + dxwrapper)
            if (dxwrapper.contains("dxvk")) {
                envVars.put("WINE_D3D_CONFIG", "renderer=gdi")
            }
            if (changed) {
                extractGraphicsDriverComponent(context, "vortek-2.1", rootDir)
                extractGraphicsDriverComponent(context, "zink-22.2.5", rootDir)
            }
        }
    } else {
        var adrenoToolsDriverId: String? = ""
        val selectedDriverVersion: String?
        val graphicsDriverConfig = KeyValueSet(container.getGraphicsDriverConfig())
        val imageFs = ImageFs.find(context)

        val currentWrapperVersion: String? = graphicsDriverConfig.get("version", DefaultVersion.WRAPPER)
        val isAdrenotoolsTurnip: String? = graphicsDriverConfig.get("adrenotoolsTurnip", "1") // Default to "1"

        selectedDriverVersion = currentWrapperVersion

        adrenoToolsDriverId =
            if (selectedDriverVersion!!.contains(DefaultVersion.WRAPPER)) DefaultVersion.WRAPPER else selectedDriverVersion
        Log.d("GraphicsDriverExtraction", "Adrenotools DriverID: " + adrenoToolsDriverId)

        val rootDir: File? = imageFs.getRootDir()

        if (dxwrapper.contains("dxvk")) {
            DXVKHelper.setEnvVars(context, dxwrapperConfig, envVars)
            val version = dxwrapperConfig.get("version")
            if (version == "1.11.1-sarek") {
                Timber.tag("GraphicsDriverExtraction").d("Disabling Wrapper PATCH_OPCONSTCOMP SPIR-V pass")
                envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1")
            }
        } else if (dxwrapper.contains("vkd3d")) {
            DXVKHelper.setVKD3DEnvVars(context, dxwrapperConfig, envVars)
        }

        val useDRI3: Boolean = container.isUseDRI3
        if (!useDRI3) {
            envVars.put("MESA_VK_WSI_DEBUG", "sw")
        }

        if (currentWrapperVersion.lowercase(Locale.getDefault())
                .contains("turnip") && isAdrenotoolsTurnip == "0"
        ) envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir().path + "/vulkan/icd.d/freedreno_icd.aarch64.json")
        else envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir().path + "/vulkan/icd.d/wrapper_icd.aarch64.json")
        envVars.put("GALLIUM_DRIVER", "zink")
        envVars.put("LIBGL_KOPPER_DISABLE", "true")

        if (currentWrapperVersion.lowercase(Locale.getDefault()).contains("turnip")
            && GPUInformation.isAdreno710_720_732(context)) {
            var tuDebug = envVars.get("TU_DEBUG").replace("sysmem", "gmem")
            if (!tuDebug.contains("gmem")) tuDebug = (if (tuDebug.isEmpty()) "" else "$tuDebug,") + "gmem"
            envVars.put("TU_DEBUG", tuDebug)
        }

        // 1. Get the main WRAPPER selection (e.g., "Wrapper-v2") from the class field.
        val mainWrapperSelection: String = graphicsDriver

        // 2. Get the WRAPPER that was last saved to the container's settings.
        val lastInstalledMainWrapper = container.getExtra("lastInstalledMainWrapper")

        // 3. Check if we need to extract a new wrapper file.
        if (ALWAYS_REEXTRACT || firstTimeBoot || mainWrapperSelection != lastInstalledMainWrapper) {
            // We only extract if the selection is actually a wrapper file.
            if (mainWrapperSelection.lowercase(Locale.getDefault()).startsWith("wrapper")) {
                val wrapperComponentId = mainWrapperSelection.lowercase(Locale.getDefault())
                Log.d("GraphicsDriverExtraction", "WRAPPER selection changed or first boot. Extracting: $wrapperComponentId")
                try {
                    val wrapperContentsManager = ContentsManager(context)
                    val wrapperProfile: ContentProfile? =
                        wrapperContentsManager.getProfileByEntryName(wrapperComponentId)
                    if (wrapperProfile != null) {
                        Timber.d("Applying user-defined wrapper content profile: $wrapperComponentId")
                        wrapperContentsManager.applyContent(wrapperProfile)
                    } else {
                        extractGraphicsDriverComponent(context, wrapperComponentId, rootDir!!)
                    }
                    // After success, save the new version so we don't re-extract next time.
                    container.putExtra("lastInstalledMainWrapper", mainWrapperSelection)
                    container.saveData()
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Failed to install graphics driver '$wrapperComponentId'. An internet connection is required the first time this driver is used.",
                        e,
                    )
                }
                Log.d("XServerDisplayActivity", "First time container boot, extracting extra_libs.tzst")
                extractGraphicsDriverComponent(context, "extra_libs", rootDir!!)
                val renderer = GPUInformation.getRenderer(null, null)
                if (container.wineVersion.contains("arm64ec") && renderer?.contains("Mali") != true) {
                    extractGraphicsDriverComponent(
                        context,
                        "zink_dlls",
                        File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
                    )
                }
            }
        }

        if (adrenoToolsDriverId !== "System") {
            val adrenotoolsManager: AdrenotoolsManager = AdrenotoolsManager(context)
            adrenotoolsManager.setDriverById(envVars, imageFs, adrenoToolsDriverId)
        }

        var vulkanVersion = graphicsDriverConfig.get("vulkanVersion") ?: "1.0"
        val vulkanVersionPatch = GPUHelper.vkVersionPatch()

        vulkanVersion = "$vulkanVersion.$vulkanVersionPatch"
        envVars.put("WRAPPER_VK_VERSION", vulkanVersion)

        val blacklistedExtensions: String? = graphicsDriverConfig.get("blacklistedExtensions")
        envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions)

        val gpuName = graphicsDriverConfig.get("gpuName")
        if (gpuName != "Device") {
            envVars.put("WRAPPER_DEVICE_NAME", gpuName)
            envVars.put("WRAPPER_DEVICE_ID", GPUInformation.getDeviceIdFromGPUName(context, gpuName))
            envVars.put("WRAPPER_VENDOR_ID", GPUInformation.getVendorIdFromGPUName(context, gpuName))
        }

        val maxDeviceMemory: String? = graphicsDriverConfig.get("maxDeviceMemory", "0")
        if (maxDeviceMemory != null && maxDeviceMemory.toInt() > 0)
            envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory)

        val presentMode = graphicsDriverConfig.get("presentMode")
        if (presentMode.contains("immediate")) {
            envVars.put("WRAPPER_MAX_IMAGE_COUNT", "1")
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode)

        val resourceType = graphicsDriverConfig.get("resourceType")
        envVars.put("WRAPPER_RESOURCE_TYPE", resourceType)

        val syncFrame = graphicsDriverConfig.get("syncFrame")
        if (syncFrame == "1") envVars.put("MESA_VK_WSI_DEBUG", "forcesync")

        val disablePresentWait = graphicsDriverConfig.get("disablePresentWait")
        envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait)

        val isWrapperGamenative = graphicsDriver.equals("wrapper-gamenative", ignoreCase = true)
        val vendorId = GPUInformation.getVendorID(null, null)
        val isAdreno = vendorId == 0x5143
        val isXclipse = vendorId == 0x144D
        val excludeBcnCompute = isAdreno || (isWrapperGamenative && isXclipse)
        val bcnEmulation = graphicsDriverConfig.get("bcnEmulation")
        val bcnEmulationType = graphicsDriverConfig.get("bcnEmulationType")
        when (bcnEmulation) {
            "auto" -> {
                if (bcnEmulationType.equals("compute") && !excludeBcnCompute) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "1");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "3");
            }
            "full" -> {
                if (bcnEmulationType.equals("compute") && !excludeBcnCompute) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "0");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "2");
            }
            "none" -> envVars.put("WRAPPER_EMULATE_BCN", "0")
            else -> envVars.put("WRAPPER_EMULATE_BCN", "1")
        }

        val bcnEmulationCache = graphicsDriverConfig.get("bcnEmulationCache")
        envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache)

        val transcoder = graphicsDriverConfig.get("transcoder", "cpu")
        envVars.put("WRAPPER_BCN_GPU", if (transcoder.equals("gpu", ignoreCase = true)) "1" else "0")

        val wrapperQuality = graphicsDriverConfig.get("quality", "low")
        envVars.put("WRAPPER_ASTC_BLOCK", if (wrapperQuality.equals("high", ignoreCase = true)) "4x4" else "8x8")

        if (!vkbasaltConfig.isEmpty()) {
            envVars.put("ENABLE_VKBASALT", "1")
            envVars.put("VKBASALT_CONFIG", vkbasaltConfig)
        }
    }
}

internal fun buildVkBasaltConfig(
    effect: String,
    sharpnessLevel: Int,
    sharpnessDenoise: Int,
): String {
    val normalizedEffect = effect.trim().lowercase(Locale.getDefault())
    val normalizedSharpness = sharpnessLevel.coerceIn(0, 100) / 100.0
    val normalizedDenoise = sharpnessDenoise.coerceIn(0, 100) / 100.0
    return when (normalizedEffect) {
        "cas" -> "effects=cas;casSharpness=$normalizedSharpness;enableOnLaunch=True"
        "dls" -> "effects=dls;dlsSharpness=$normalizedSharpness;dlsDenoise=$normalizedDenoise;enableOnLaunch=True"
        else -> ""
    }
}


private fun cleanupBionicSteamAssets(imageFs: ImageFs) {
    val targets = listOf(
        File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/system32/lsteamclient.dll"),
        File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/syswow64/lsteamclient.dll"),
        File(imageFs.libDir, "libsteamclient.so"),
    )
    for (target in targets) {
        if (target.exists() && !target.delete()) {
            Timber.w("Failed to delete bionic-Steam asset at ${target.absolutePath}")
        }
    }
}

private fun readZipManifestNameFromAssets(context: Context, assetName: String): String? {
    return com.winlator.core.FileUtils.readZipManifestNameFromAssets(context, assetName)
}

private fun readLibraryNameFromExtractedDir(destinationDir: File): String? {
    return try {
        val manifests = destinationDir.listFiles { _, name -> name.endsWith(".json") }
        if (manifests != null && manifests.isNotEmpty()) {
            val manifest = manifests[0]
            val content = com.winlator.core.FileUtils.readString(manifest)
            val json = org.json.JSONObject(content)
            val libraryName = json.optString("libraryName", "").trim()
            if (libraryName.isNotEmpty()) libraryName else null
        } else null
    } catch (_: Exception) {
        null
    }
}
internal fun changeWineAudioDriver(audioDriver: String, container: Container, imageFs: ImageFs) {
    if (audioDriver != container.getExtra("audioDriver")) {
        val rootDir = imageFs.rootDir
        val userRegFile = File(rootDir, ImageFs.WINEPREFIX + "/user.reg")
        WineRegistryEditor(userRegFile).use { registryEditor ->
            if (audioDriver == "alsa") {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa")
            } else if (audioDriver == "pulseaudio") {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse")
            } else if (audioDriver == "disabled") {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "")
            }
        }
        container.putExtra("audioDriver", audioDriver)
        container.saveData()
    }
}
internal fun setImagefsContainerVariant(context: Context, container: Container) {
    val imageFs = ImageFs.find(context)
    val containerVariant = container.containerVariant
    imageFs.createVariantFile(containerVariant)
}
