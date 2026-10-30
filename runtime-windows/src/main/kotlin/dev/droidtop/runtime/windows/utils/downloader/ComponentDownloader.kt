package dev.droidtop.runtime.windows.utils.downloader

import android.content.Context
import dev.droidtop.runtime.windows.RuntimeDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * The one downloader for the runtime's component files (Droidtop/tracker#765): core drivers (Adreno, SD8Elite), Wine
 * components, DX wrappers (DXVK, VKD3D, D8VK) and graphics drivers (Turnip, Zink, Virgl, Vortek, Adrenotools,
 * wrappers). Each [Kind] has its own manifest in the runtime's assets and its own cache folder; a component is
 * fetched once from the component server ([RuntimeDownloads]) and taken from the cache after that.
 */
object ComponentDownloader {

    enum class Kind(
        val manifestFile: String,
        /** The folder under the app's files (as `assets/<folder>`) and on the server. */
        val folder: String,
        private val label: String,
        /** The file a component is stored as: by its id for some kinds, by the manifest's name for others. */
        private val fileOf: (id: String, component: Component) -> String,
    ) {
        CORE_DRIVER("core_drivers_download.json", "core_drivers", "core driver", { id, _ -> "$id.zip" }),
        WIN_COMPONENT("wincomponents_download.json", "wincomponents", "wincomponent", { id, _ -> "$id.tzst" }),

        // The manifest's name, which also covers a .tar.xz graphics driver.
        DX_WRAPPER("dxwrapper_download.json", "dxwrapper", "dxwrapper", { _, component -> component.name }),
        GRAPHICS_DRIVER("graphics_driver_download.json", "graphics_driver", "graphics driver", { _, component -> component.name }),
        ;

        override fun toString(): String = label

        internal fun fileName(id: String, component: Component): String = fileOf(id, component)
    }

    @Serializable
    data class Manifest(
        val version: Int,
        val updatedAt: String,
        val components: List<Component>,
    )

    @Serializable
    data class Component(
        val id: String,
        val name: String,
        val url: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Where [kind]'s components are cached. */
    fun cacheDir(context: Context, kind: Kind): File = File(context.filesDir, "assets/${kind.folder}")

    /**
     * The file of [kind]'s component [componentId], from the cache or downloaded into it; null when [kind]'s manifest
     * has no such component. A failed download throws, leaving nothing half-written in the cache.
     */
    suspend fun ensure(
        context: Context,
        kind: Kind,
        componentId: String,
        onProgress: (Float) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        val component = loadManifest(context, kind).components.find { it.id == componentId }
            ?: return@withContext null
        val fileName = kind.fileName(componentId, component)
        val destFile = File(cacheDir(context, kind), fileName)
        if (destFile.exists() && destFile.length() > 0) {
            Timber.d("Using cached $kind: $componentId at ${destFile.absolutePath}")
            return@withContext destFile
        }

        Timber.i("Downloading $kind: $componentId from server")
        destFile.parentFile?.mkdirs()
        try {
            RuntimeDownloads.fetch(context, fileName = "${kind.folder}/$fileName", dest = destFile, onProgress = onProgress)
            Timber.i("Successfully downloaded $kind: $componentId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to download $kind: $componentId")
            destFile.delete()
            throw e
        }
        destFile
    }

    /** As [ensure], for a component that must be in [kind]'s manifest: throws when it is not. */
    suspend fun require(context: Context, kind: Kind, componentId: String, onProgress: (Float) -> Unit = {}): File =
        ensure(context, kind, componentId, onProgress)
            ?: throw Exception("$kind $componentId not found in ${kind.manifestFile}")

    private fun loadManifest(context: Context, kind: Kind): Manifest = try {
        context.assets.open(kind.manifestFile).bufferedReader().use { json.decodeFromString<Manifest>(it.readText()) }
    } catch (e: Exception) {
        Timber.e(e, "Failed to load ${kind.manifestFile}")
        throw Exception("Failed to load the $kind manifest: ${e.message}", e)
    }
}
