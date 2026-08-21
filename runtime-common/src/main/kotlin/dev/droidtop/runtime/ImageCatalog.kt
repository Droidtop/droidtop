package dev.droidtop.runtime

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A droidtop-known OCI *repository* — an OS or OS+compositor combo worth
 * showing in the sortable/filterable container-creation UI (docs/SPEC.md
 * §3a) — but deliberately NOT a specific version/tag/digest. Which
 * tags/versions actually exist right now is discovered live via
 * [ImageCatalogResolver] against the real registry, not pinned ahead of
 * time: OCI's Distribution API has no "list every distro image that
 * exists" endpoint (`crane ls` needs an already-known repository name), so
 * this bundled list is the unavoidable minimum seed — "which repositories
 * are worth looking at" — not a "which versions we recommend" snapshot
 * that would go stale the moment a new release ships.
 *
 * PRIMARY entries are the one real exception: droidtop hasn't published
 * any base+compositor image anywhere yet, so [repository] for those is
 * still a placeholder with nothing to resolve tags against (see
 * `known-image-repositories.json`'s own PRIMARY rows).
 *
 * [arm64Available]/[officialSource]/[headlessSupport] carry real research
 * findings that a bare repository name wouldn't reveal: e.g. the official
 * `archlinux/archlinux` Docker image is amd64-only — no arm64 build at all
 * (Arch Linux's own tracking issue,
 * gitlab.archlinux.org/archlinux/archlinux-docker/-/issues/29) — which
 * matters enormously on droidtop's ARM64-only target hardware.
 */
@Serializable
data class KnownImageRepository(
    val id: String,
    val os: String,
    /** e.g. "sway", "labwc", "hyprland"; null for a desktop-environment-less sibling repository. */
    val desktopEnvironment: String? = null,
    val compositorFamily: CompositorFamily? = null,
    val headlessSupport: HeadlessSupport = HeadlessSupport.NOT_APPLICABLE,
    val role: ImageCatalogRole,
    /**
     * Just a repository path (e.g. "library/alpine", "opensuse/tumbleweed")
     * — resolved against [registry] (defaults to Docker Hub, droidtop's
     * default OCI source) to build the reference [ImageCatalogResolver]
     * queries. No tag here on purpose — see this class's own doc comment.
     */
    val repository: String,
    /** Registry host; defaults to Docker Hub, droidtop's default OCI source. */
    val registry: String = DEFAULT_REGISTRY,
    /** True if [repository] is the distro/vendor's own registry namespace; false for a third-party rebuild (e.g. an unofficial arm64 fork of an amd64-only official image). */
    val officialSource: Boolean,
    /** droidtop only targets ARM64 devices — an entry without this is unusable here regardless of anything else, the single most important filter. */
    val arm64Available: Boolean,
    /** Free-text caveat citing the actual research finding — e.g. why arm64Available is false, or a known upstream regression. */
    val notes: String? = null,
)

/** Docker Hub — droidtop's default OCI registry when a [KnownImageRepository] doesn't specify one. */
const val DEFAULT_REGISTRY = "docker.io"

/**
 * Whether an entry is meaningful as the device's one [ContainerRole.PRIMARY]
 * container (needs a compositor pre-installed to serve as the shared
 * desktop), a [ContainerRole.SIBLING] (no compositor needed), or both.
 */
@Serializable
enum class ImageCatalogRole { PRIMARY, SIBLING, BOTH }

/**
 * Which backend a listed compositor is built on — the actual reason
 * [HeadlessSupport] differs between entries. wlroots ships a headless
 * backend as core infrastructure (`WLR_BACKENDS=headless`), which every
 * wlroots-based compositor inherits; Hyprland forked off wlroots onto its
 * own Aquamarine backend in 2024 and its headless-output support has since
 * had multiple open upstream regressions (hyprwm/Hyprland#7917, #8806).
 */
@Serializable
enum class CompositorFamily { WLROOTS, AQUAMARINE, X11 }

/**
 * How confident droidtop is that a compositor actually boots headless —
 * the property that matters most for a PRIMARY entry, since the primary
 * container has no real display attached (§2/§4).
 */
@Serializable
enum class HeadlessSupport {
    /** Actually verified — by droidtop, or by clear, specific upstream documentation/prior art (e.g. sway's ArchWiki/Gentoo-wiki headless instructions). */
    CONFIRMED,
    /** Same compositor family as a CONFIRMED entry (wlroots), but this specific compositor's headless path hasn't been independently checked. */
    LIKELY,
    /** Has shipped headless support that current upstream issue trackers show as broken right now. */
    REGRESSED,
    UNSUPPORTED,
    /** No compositor in this entry (a SIBLING repository) — the field doesn't apply. */
    NOT_APPLICABLE,
}

@Serializable
data class KnownImageRepositoryList(val version: Int, val repositories: List<KnownImageRepository>)

/**
 * One live-resolved tag for a [KnownImageRepository] — the actual row a
 * sortable/filterable image-selection UI would show, populated at runtime
 * rather than baked into the bundled seed list.
 */
data class ResolvedImage(
    val repository: KnownImageRepository,
    val tag: String,
    val digest: String,
) {
    fun toRootfsImage(): RootfsImage =
        RootfsImage(reference = "${repository.registry}/${repository.repository}:$tag", digest = digest)
}

/**
 * Queries a real OCI registry for what's actually published under a
 * [KnownImageRepository] — this is where droidtop's "populate at runtime,
 * don't prepopulate the catalog" model (docs/SPEC.md §3a) actually lives.
 * [CraneRootfsPuller] already performs the equivalent "resolve one known
 * reference to a digest" operation for pulls; this interface is the same
 * category of thing, but for *discovering* what tags exist in the first
 * place, one repository at a time (no cross-repository search — the OCI
 * Distribution API doesn't offer one).
 */
interface ImageCatalogResolver {
    /** Every tag currently published under [repository], as reported by the registry (e.g. via `crane ls`). */
    suspend fun listTags(repository: KnownImageRepository): List<String>

    /** Resolves one specific [tag] to its current digest. */
    suspend fun resolve(repository: KnownImageRepository, tag: String): ResolvedImage
}

/**
 * Loads droidtop's bundled seed list of known repositories from APK assets
 * (`runtime-common/src/main/assets/known-image-repositories.json`) — just
 * "which OCI repositories are worth showing," not a version/tag snapshot.
 * Only source right now; a remote-refresh path for the seed list itself
 * (distinct from live tag resolution, which is always live) isn't decided
 * or built (docs/SPEC.md §3a).
 */
object BundledImageRepositories {
    private const val ASSET_PATH = "known-image-repositories.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): KnownImageRepositoryList {
        val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        return parse(text)
    }

    /** Split out from [load] so a plain JVM unit test can exercise real parsing without an Android [Context]. */
    internal fun parse(text: String): KnownImageRepositoryList = json.decodeFromString(KnownImageRepositoryList.serializer(), text)
}
