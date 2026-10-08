package dev.droidtop.runtime.windows.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.contents.ContentProfile
import com.winlator.core.KeyValueSet
import dev.droidtop.runtime.windows.R
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/**
 * Which components a prefix's settings name that are neither bundled nor
 * installed, and where in droidtop's component catalog each one is
 * ([ComponentCatalog.runnable]): GameNative's
 * `BestConfigService.resolveMissingManifestInstallRequests` (GPL-3.0), the
 * one part of that service droidtop uses. Its online config lookup and
 * GPU-family overrides are not carried: droidtop asks with a prefix's own
 * settings, which GameNative's "exact_gpu_match" left untouched.
 */
object ComponentRequests {
    data class ManifestInstallRequest(
        val entry: ManifestEntry,
        val contentType: ContentProfile.ContentType? = null,
        val isDriver: Boolean = false,
    )

    suspend fun resolveMissing(context: Context, configJson: JsonObject): List<ManifestInstallRequest> {
        val filteredJson = JSONObject(configJson.toString())
        val installed = ManifestComponentHelper.loadInstalledContentLists(context)
        val manifest = ComponentCatalog.runnable(context)
        val installedContent = installed.installed

        val containerVariant = filteredJson.optString("containerVariant", "")
        val dxwrapper = filteredJson.optString("dxwrapper", "")
        val dxwrapperConfig = filteredJson.optString("dxwrapperConfig", "")
        val box64Version = filteredJson.optString("box64Version", "")
        val wineVersion = filteredJson.optString("wineVersion", "")
        val emulator = filteredJson.optString("emulator", "")
        val fexcoreVersion = filteredJson.optString("fexcoreVersion", "")
        val graphicsDriverConfig = filteredJson.optString("graphicsDriverConfig", "")

        val manifestDxvk = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.DXVK].orEmpty(),
            containerVariant,
        )
        val manifestVkd3d = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.VKD3D].orEmpty(),
            containerVariant,
        )
        val manifestBox64 = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.BOX64].orEmpty(),
            containerVariant,
        )
        val manifestWowBox64 = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.WOWBOX64].orEmpty(),
            containerVariant,
        )
        val manifestFexcore = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.FEXCORE].orEmpty(),
            containerVariant,
        )
        val manifestDrivers = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.DRIVER].orEmpty(),
            containerVariant,
        )
        val manifestWine = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.WINE].orEmpty(),
            containerVariant,
        )
        val manifestProton = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.PROTON].orEmpty(),
            containerVariant,
        )

        // Build locally available versions upfront (base + installed, NO manifest)
        val baseDxvk = context.resources.getStringArray(R.array.dxvk_version_entries).toList()
        val baseVkd3d = context.resources.getStringArray(R.array.vkd3d_version_entries).toList()
        val baseBox64Bionic = context.resources.getStringArray(R.array.box64_bionic_version_entries).toList()
        val baseBox64Glibc = context.resources.getStringArray(R.array.box64_version_entries).toList()
        val baseWowBox64 = context.resources.getStringArray(R.array.wowbox64_version_entries).toList()
        val baseFexcore = context.resources.getStringArray(R.array.fexcore_version_entries).toList()
        val baseWineBionic = context.resources.getStringArray(R.array.bionic_wine_entries).toList()
        val baseWineGlibc = context.resources.getStringArray(R.array.glibc_wine_entries).toList()
        val baseDrivers = ManifestComponentHelper.bundledGraphicsDriverBase(
            context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries).toList(),
        )

        val locallyAvailableDxvk = ManifestComponentHelper.buildAvailableVersions(
            base = baseDxvk,
            installed = installedContent.dxvk,
            manifest = emptyList(),
        )
        val locallyAvailableVkd3d = ManifestComponentHelper.buildAvailableVersions(
            base = baseVkd3d,
            installed = installedContent.vkd3d,
            manifest = emptyList(),
        )
        val locallyAvailableBox64Bionic = ManifestComponentHelper.buildAvailableVersions(
            base = baseBox64Bionic,
            installed = installedContent.box64,
            manifest = emptyList(),
        )
        val locallyAvailableBox64Glibc = ManifestComponentHelper.buildAvailableVersions(
            base = baseBox64Glibc,
            installed = installedContent.box64,
            manifest = emptyList(),
        )
        val locallyAvailableWowBox64 = ManifestComponentHelper.buildAvailableVersions(
            base = baseWowBox64,
            installed = installedContent.wowBox64,
            manifest = emptyList(),
        )
        val locallyAvailableFexcore = ManifestComponentHelper.buildAvailableVersions(
            base = baseFexcore,
            installed = installedContent.fexcore,
            manifest = emptyList(),
        )
        val locallyAvailableWineBionic = ManifestComponentHelper.buildAvailableVersions(
            base = baseWineBionic,
            installed = installedContent.wine + installedContent.proton,
            manifest = emptyList(),
        )
        val locallyAvailableWineGlibc = ManifestComponentHelper.buildAvailableVersions(
            base = baseWineGlibc,
            installed = installedContent.wine + installedContent.proton,
            manifest = emptyList(),
        )
        val locallyAvailableDrivers = ManifestComponentHelper.buildAvailableVersions(
            base = baseDrivers,
            installed = installed.installedDrivers,
            manifest = emptyList(),
        )

        val requests = LinkedHashMap<String, ManifestInstallRequest>()
        fun addRequest(entry: ManifestEntry, contentType: ContentProfile.ContentType? = null, isDriver: Boolean = false) {
            val key = entry.id.lowercase(Locale.ENGLISH)
            if (!requests.containsKey(key)) {
                requests[key] = ManifestInstallRequest(entry = entry, contentType = contentType, isDriver = isDriver)
            }
        }

        if (dxwrapper == "dxvk" && dxwrapperConfig.isNotEmpty()) {
            val kvs = KeyValueSet(dxwrapperConfig)
            val version = kvs.get("version")
            if (version.isNotEmpty() && !ManifestComponentHelper.versionExists(version, locallyAvailableDxvk)) {
                // Not locally available: if it exists in the manifest, enqueue it for download
                val entry = ManifestComponentHelper.findManifestEntryForVersion(version, manifestDxvk)
                if (entry != null) {
                    addRequest(entry, ContentProfile.ContentType.CONTENT_TYPE_DXVK)
                }
            }
        }

        if (dxwrapper == "vkd3d" && dxwrapperConfig.isNotEmpty()) {
            val kvs = KeyValueSet(dxwrapperConfig)
            val version = kvs.get("vkd3dVersion")
            if (version.isNotEmpty() && !ManifestComponentHelper.versionExists(version, locallyAvailableVkd3d)) {
                val entry = ManifestComponentHelper.findManifestEntryForVersion(version, manifestVkd3d)
                if (entry != null) {
                    addRequest(entry, ContentProfile.ContentType.CONTENT_TYPE_VKD3D)
                }
            }
        }

        if (box64Version.isNotEmpty() && containerVariant.isNotEmpty()) {
            val locallyAvailableBox64 = when {
                containerVariant.equals(Container.BIONIC, ignoreCase = true) -> locallyAvailableBox64Bionic
                containerVariant.equals(Container.GLIBC, ignoreCase = true) -> locallyAvailableBox64Glibc
                else -> locallyAvailableBox64Glibc
            }
            if (!ManifestComponentHelper.versionExists(box64Version, locallyAvailableBox64)) {
                val entry = ManifestComponentHelper.findManifestEntryForVersion(box64Version, manifestBox64)
                if (entry != null) {
                    addRequest(entry, ContentProfile.ContentType.CONTENT_TYPE_BOX64)
                }
            }
        }

        if (wineVersion.contains("arm64ec", ignoreCase = true) && emulator != "FEXCore") {
            if (box64Version.isNotEmpty() && !ManifestComponentHelper.versionExists(box64Version, locallyAvailableWowBox64)) {
                val entry = ManifestComponentHelper.findManifestEntryForVersion(box64Version, manifestWowBox64)
                if (entry != null) {
                    addRequest(entry, ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64)
                }
            }
        }

        if (fexcoreVersion.isNotEmpty()) {
            if (!ManifestComponentHelper.versionExists(fexcoreVersion, locallyAvailableFexcore)) {
                val entry = ManifestComponentHelper.findManifestEntryForVersion(fexcoreVersion, manifestFexcore)
                if (entry != null) {
                    addRequest(entry, ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)
                }
            }
        }

        if (wineVersion.isNotEmpty() && containerVariant.isNotEmpty()) {
            val locallyAvailableWine = when {
                containerVariant.equals(Container.BIONIC, ignoreCase = true) -> locallyAvailableWineBionic
                containerVariant.equals(Container.GLIBC, ignoreCase = true) -> locallyAvailableWineGlibc
                else -> (locallyAvailableWineBionic + locallyAvailableWineGlibc).distinct()
            }
            if (!ManifestComponentHelper.versionExists(wineVersion, locallyAvailableWine)) {
                val wineEntry = ManifestComponentHelper.findManifestEntryForVersion(wineVersion, manifestWine)
                val protonEntry = if (wineEntry == null) {
                    ManifestComponentHelper.findManifestEntryForVersion(wineVersion, manifestProton)
                } else null
                when {
                    wineEntry != null -> addRequest(wineEntry, ContentProfile.ContentType.CONTENT_TYPE_WINE)
                    protonEntry != null -> addRequest(protonEntry, ContentProfile.ContentType.CONTENT_TYPE_PROTON)
                }
            }
        }

        if (containerVariant.equals(Container.BIONIC, ignoreCase = true) && graphicsDriverConfig.isNotEmpty()) {
            val sep = if (graphicsDriverConfig.contains(";")) ";" else ","
            val driverVersion = graphicsDriverConfig.split(sep)
                .firstOrNull { it.substringBefore("=", "") == "version" }
                ?.substringAfter("=", "")
                .orEmpty()
            if (driverVersion.isNotEmpty()) {
                val entry = ManifestComponentHelper.findManifestEntryForVersion(driverVersion, manifestDrivers)
                if (entry != null &&
                    !ManifestComponentHelper.versionExists(driverVersion, locallyAvailableDrivers) &&
                    !ManifestComponentHelper.versionExists(entry.id, locallyAvailableDrivers)
                ) {
                    addRequest(entry, isDriver = true)
                }
            }
        }

        return requests.values.toList()
    }
}
