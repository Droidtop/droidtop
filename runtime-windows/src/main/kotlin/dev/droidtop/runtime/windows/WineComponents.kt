package dev.droidtop.runtime.windows

import android.content.Context
import dev.droidtop.runtime.windows.R
import dev.droidtop.runtime.windows.utils.ComponentRequests
import dev.droidtop.runtime.windows.utils.ManifestContentTypes
import dev.droidtop.runtime.windows.utils.ManifestInstaller
import dev.droidtop.runtime.windows.utils.ManifestRepository
import dev.droidtop.runtime.windows.utils.X86_64GuestLibs
import dev.droidtop.runtime.windows.utils.X86_64Graphics
import com.winlator.container.Container
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.TarCompressorUtils
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
 * GameNative's download host ([RuntimeDownloads]), everything else
 * through upstream GameNative's component manifest, resolved by
 * [ComponentRequests.resolveMissing] and installed by
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
     * are archives on GameNative's download host ([RuntimeDownloads]),
     * unpacked into the shared Proton store. A manifest build (arm64ec
     * Proton 10 and later) is a `.wcp` the manifest installer puts into the
     * contents store.
     */
    suspend fun ensureWine(context: Context, container: Container, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        val wine = container.wineVersion
        if (isBundledProton(context, wine)) {
            val archive = File(context.filesDir, "$wine.txz")
            if (!wineBinary(context, wine).isFile && !(archive.isFile && archive.length() > 0)) {
                onStatus("Downloading Wine…")
                RuntimeDownloads.fetch(archive.name, archive) { onStatus("Downloading Wine… ${percent(it)}") }
            }
            // Into the shared Proton store, where ImageFsInstaller links
            // opt/<build> from (GameNative's BionicDefaultProtonDependency).
            val outDir = File(ImageFs.getSharedProtonDir(context), wine)
            if (!File(outDir, "bin").isDirectory) {
                onStatus("Installing Wine…")
                check(TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, archive, outDir)) {
                    "couldn't unpack $wine from ${archive.absolutePath}"
                }
            }
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

    private suspend fun manifestRequests(context: Context, container: Container): List<ComponentRequests.ManifestInstallRequest> {
        // The saved config, with a game's launch-time choices over it: what
        // this launch will actually ask for.
        val saved = Json.parseToJsonElement(container.containerJson).jsonObject
        val config = JsonObject(saved + container.launchOverrides.mapValues { JsonPrimitive(it.value) })
        return ComponentRequests.resolveMissing(context, config)
            // On x86_64 the arm64 drivers are never used (X86_64Graphics).
            .filterNot { X86_64GuestLibs.isX86_64Host() && it.isDriver }
    }

    private fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"
}
