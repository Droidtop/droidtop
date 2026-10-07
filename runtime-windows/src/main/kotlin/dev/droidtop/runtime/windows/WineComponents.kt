package dev.droidtop.runtime.windows

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.LaunchDependencies
import app.gamenative.utils.ManifestContentTypes
import app.gamenative.utils.ManifestInstaller
import app.gamenative.utils.ManifestRepository
import app.gamenative.utils.X86_64GuestLibs
import app.gamenative.utils.X86_64Graphics
import com.winlator.container.Container
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.WineInfo
import com.winlator.xenvironment.ImageFs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Everything a prefix's settings name that has to be on the device before
 * it can start: the Wine build, the DXVK/VKD3D versions, the FEXCore or Box64
 * version, the graphics driver, and on an x86_64 device the guest libraries
 * and the x86_64 graphics driver.
 *
 * One step for setup, for the Wine settings screen's "Download" row and for
 * every launch, so a choice made anywhere is fetched the same way. Nothing
 * here is droidtop's own downloader: the two Proton 9 builds come through
 * gamenative's [LaunchDependencies] (as setup always did), everything else
 * through upstream GameNative's component manifest, resolved by
 * [BestConfigService.resolveMissingManifestInstallRequests] and installed by
 * [ManifestInstaller] -- exactly what GameNative's own pre-launch does
 * (PluviaMain.preLaunchApp).
 */
internal object WineComponents {

    /**
     * Fetches whatever [container] names and the device lacks. Throws with a
     * message a person can act on; [onStatus] carries progress lines.
     */
    suspend fun ensure(context: Context, container: Container, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (X86_64GuestLibs.isX86_64Host()) {
            if (!X86_64GuestLibs.isInstalled(context)) {
                onStatus("Downloading the x86_64 Windows libraries…")
                X86_64GuestLibs.ensureInstalled(context) { onStatus("Downloading the x86_64 Windows libraries… ${percent(it)}") }
            }
            val driver = container.graphicsDriver
            if (!X86_64Graphics.isInstalled(context, driver)) {
                onStatus("Downloading the $driver graphics driver…")
                X86_64Graphics.ensureInstalled(context, driver) { onStatus("Downloading the $driver graphics driver… ${percent(it)}") }
            }
        }
        ensureWine(context, container, onStatus)
        val failed = mutableListOf<String>()
        for (request in manifestRequests(context, container)) {
            val name = request.entry.name
            onStatus("Downloading $name…")
            val result = ManifestInstaller.installManifestEntry(context, request.entry, request.isDriver, request.contentType) {
                onStatus("Downloading $name… ${percent(it)}")
            }
            if (!result.success) failed += result.message
        }
        if (failed.isNotEmpty()) error(failed.joinToString("; "))
    }

    /** What [ensure] would download for [container], by name, without downloading anything. Disk and network. */
    suspend fun missing(context: Context, container: Container): List<String> = withContext(Dispatchers.IO) {
        buildList {
            if (X86_64GuestLibs.isX86_64Host()) {
                if (!X86_64GuestLibs.isInstalled(context)) add("the x86_64 Windows libraries")
                if (!X86_64Graphics.isInstalled(context, container.graphicsDriver)) add("the ${container.graphicsDriver} graphics driver")
            }
            if (isBundledProton(context, container.wineVersion) && !wineBinary(context, container.wineVersion).isFile) {
                add(container.wineVersion)
            }
            runCatching { manifestRequests(context, container) }.getOrDefault(emptyList()).forEach { add(it.entry.name) }
        }
    }

    /**
     * The Wine build itself. The two Proton 9 builds (`bionic_wine_entries`)
     * come from gamenative's own launch dependency, fetched from its download
     * host ([GameNativeDownloads]). A manifest build (arm64ec
     * Proton 10 and later) is a `.wcp` the manifest installer puts into the
     * contents store.
     */
    suspend fun ensureWine(context: Context, container: Container, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        val wine = container.wineVersion
        if (isBundledProton(context, wine)) {
            val archive = File(context.filesDir, "$wine.txz")
            if (!wineBinary(context, wine).isFile && !(archive.isFile && archive.length() > 0)) {
                onStatus("Downloading Wine…")
                GameNativeDownloads.fetch(archive.name, archive) { onStatus("Downloading Wine… ${percent(it)}") }
            }
            LaunchDependencies().ensureLaunchDependencies(
                context = context,
                container = container,
                gameSource = GameSource.CUSTOM_GAME,
                gameId = 0,
                setLoadingMessage = { message -> onStatus(message) },
                setLoadingProgress = { fraction -> if (fraction >= 0f) onStatus("Installing Wine… ${percent(fraction)}") },
            )
            return@withContext
        }
        if (wineBinary(context, wine).isFile) return@withContext
        val manifest = ManifestRepository.loadManifest(context)
        val (entry, type) = manifest.items[ManifestContentTypes.PROTON].orEmpty().firstOrNull { it.id == wine }
            ?.let { it to ContentProfile.ContentType.CONTENT_TYPE_PROTON }
            ?: manifest.items[ManifestContentTypes.WINE].orEmpty().firstOrNull { it.id == wine }
                ?.let { it to ContentProfile.ContentType.CONTENT_TYPE_WINE }
            ?: error("$wine is neither installed nor in the component list; pick another Wine build")
        onStatus("Downloading ${entry.name}…")
        val result = ManifestInstaller.installManifestEntry(context, entry, isDriver = false, contentType = type) {
            onStatus("Downloading ${entry.name}… ${percent(it)}")
        }
        if (!result.success) error(result.message)
    }

    /**
     * Where [wineVersion]'s wine binary is. A proton build resolves to its
     * own directory (a symlink into the shared proton store, or the contents
     * store for a `.wcp`); the plain default resolves to `opt/wine`.
     */
    fun wineBinary(context: Context, wineVersion: String): File {
        val imageFs = ImageFs.find(context)
        val contentsManager = ContentsManager(context).apply { syncContents() }
        val info = runCatching { WineInfo.fromIdentifier(context, contentsManager, wineVersion) }.getOrNull()
        val root = info?.path?.takeIf { it.isNotEmpty() }
            ?: return File(imageFs.rootDir, "opt/wine/bin/wine")
        return File(root, "bin/wine")
    }

    private fun isBundledProton(context: Context, wineVersion: String): Boolean =
        context.resources.getStringArray(R.array.bionic_wine_entries).any { it.equals(wineVersion, ignoreCase = true) }

    private suspend fun manifestRequests(context: Context, container: Container): List<BestConfigService.ManifestInstallRequest> {
        // The saved config, with a game's launch-time choices over it: what
        // this launch will actually ask for.
        val saved = Json.parseToJsonElement(container.containerJson).jsonObject
        val config = JsonObject(saved + container.launchOverrides.mapValues { JsonPrimitive(it.value) })
        return BestConfigService.resolveMissingManifestInstallRequests(context, config, "exact_gpu_match")
            // On x86_64 the arm64 drivers are never used (X86_64Graphics).
            .filterNot { X86_64GuestLibs.isX86_64Host() && it.isDriver }
    }

    private fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"
}
