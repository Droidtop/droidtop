package dev.droidtop.runtime.windows.utils

import kotlinx.serialization.Serializable

/**
 * One component the catalog offers ([ComponentCatalog]). [id] is what a
 * prefix names (and what the runtime installs it as), [url] where it is
 * downloaded from, [sha256] what the download is checked against. [source]
 * is the catalog source it came from, [engine] what can run it: `bionic`
 * (droidtop's Windows runtime) or `linux-glibc` (a glibc userland droidtop
 * does not run Wine in yet). Upstream GameNative's manifest entries had only
 * the first five fields; the rest default so its JSON still reads.
 */
@Serializable
data class ManifestEntry(
    val id: String,
    val name: String,
    val url: String,
    val variant: String? = null,
    val arch: String? = null,
    val sha256: String? = null,
    val size: Long = 0,
    val source: String = ComponentCatalog.SOURCE_MIRROR,
    val engine: String = ComponentCatalog.ENGINE_BIONIC,
)

/** A catalog source, as droidtop-components' sources/feeds.json describes it. */
@Serializable
data class CatalogSource(
    val id: String,
    val label: String,
    val kind: String,
    val engine: String = ComponentCatalog.ENGINE_BIONIC,
    val defaultEnabled: Boolean = false,
    val about: String? = null,
    val homepage: String? = null,
)

/** A base-system file the runtime fetches by path (`imagefs_bionic.txz`, `dxwrapper/dxvk-2.7.1.tzst`). */
@Serializable
data class CatalogFile(val url: String, val sha256: String? = null, val size: Long = 0)

@Serializable
data class ManifestData(
    val version: Int?,
    val updatedAt: String?,
    val items: Map<String, List<ManifestEntry>>,
    val sources: List<CatalogSource> = emptyList(),
    val files: Map<String, CatalogFile> = emptyMap(),
) {
    companion object {
        fun empty(): ManifestData = ManifestData(null, null, emptyMap())
    }
}

object ManifestContentTypes {
    const val DRIVER = "driver"
    const val DXVK = "dxvk"
    const val VKD3D = "vkd3d"
    const val BOX64 = "box64"
    const val WOWBOX64 = "wowbox64"
    const val FEXCORE = "fexcore"
    const val WINE = "wine"
    const val PROTON = "proton"
}
