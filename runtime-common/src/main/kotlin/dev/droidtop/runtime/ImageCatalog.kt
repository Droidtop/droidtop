package dev.droidtop.runtime

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One recommended image: an OS + optional desktop-environment/compositor
 * combo droidtop suggests as a one-tap choice in the container-creation UI
 * (see docs/SPEC.md §3a) — bundled as data, not a restriction. The "custom
 * image reference" path from [RootfsPuller]/[RootfsImage] stays available
 * for anything not in here; [toRootfsImage] is the only bridge a catalog
 * entry needs to be pulled the same way a hand-typed reference would be.
 *
 * PRIMARY entries point at droidtop's own published base+compositor images
 * (a stock upstream Alpine/Debian/etc. image has no compositor pre-
 * installed to boot a desktop with — see [ImageCatalogRole.PRIMARY]'s own
 * doc). None of those are published yet, matching
 * `DroidSpacesRuntime.PRIMARY_IMAGE_REFERENCE`'s own placeholder. SIBLING
 * entries are plain stock distro images — no compositor needed, since the
 * primary container's compositor serves every container — so those
 * reference real upstream registries today.
 */
@Serializable
data class ImageCatalogEntry(
    val id: String,
    val os: String,
    val osVersion: String,
    /** e.g. "sway", "labwc"; null for a desktop-environment-less sibling image. */
    val desktopEnvironment: String? = null,
    val role: ImageCatalogRole,
    /**
     * A floating OCI reference (e.g. "docker.io/library/alpine:3.20"), the
     * same shape [RootfsPuller.resolve] already takes — resolving it to a
     * concrete digest at pull time is how droidtop's SPEC §3a "autodetect
     * new versions" story works: this field is a pointer, not a pin.
     */
    val imageReference: String,
    /** True only once someone has actually booted this image and confirmed it works — not "listed" vs. "untested". */
    val verified: Boolean,
)

/**
 * Whether an entry is meaningful as the device's one [ContainerRole.PRIMARY]
 * container (needs a compositor pre-installed to serve as the shared
 * desktop), a [ContainerRole.SIBLING] (no compositor needed), or both.
 */
@Serializable
enum class ImageCatalogRole { PRIMARY, SIBLING, BOTH }

@Serializable
data class ImageCatalog(val version: Int, val entries: List<ImageCatalogEntry>)

fun ImageCatalogEntry.toRootfsImage(): RootfsImage = RootfsImage(reference = imageReference)

/**
 * Loads droidtop's bundled recommended-image catalog from APK assets
 * (`runtime-common/src/main/assets/image-catalog.json`). Only source right
 * now — the remote-refresh path SPEC §3a leaves undecided isn't built.
 */
object BundledImageCatalog {
    private const val ASSET_PATH = "image-catalog.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): ImageCatalog {
        val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        return json.decodeFromString(ImageCatalog.serializer(), text)
    }
}
