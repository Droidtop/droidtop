package dev.droidtop.runtime

/**
 * A container's root filesystem source, expressed as an OCI image reference
 * — the same addressing scheme `docker pull`/`podman pull` use — rather than
 * a bespoke tarball format. Pulled and unpacked on-device via vendor/crane
 * (from vendor/go-containerregistry): no Docker daemon involved, just an
 * OCI registry client that fetches layer blobs and extracts them.
 *
 * This means the primary container's base image (a minimal distro + the
 * vendor/sway build) and any sibling container's base image (Ubuntu,
 * Debian, Alpine, Arch — whatever a user wants their "distrobox" to be) are
 * both just image references, pullable from Docker Hub, GHCR, or a private
 * registry, and cacheable by digest so re-creating a container doesn't
 * re-download anything unchanged.
 */
data class RootfsImage(
    val reference: String, // e.g. "docker.io/library/debian:bookworm" or a private registry ref
    val digest: String? = null, // pin by digest once resolved, for reproducible/cached pulls
)

/**
 * User-facing choice, not just an implementation detail: keeping pulled
 * layers around means re-creating/duplicating a container (e.g. spinning up
 * a second Debian sibling) or reinstalling after clearing app data doesn't
 * re-download anything, at the cost of on-device storage. Should be exposed
 * as an actual setting (with a size cap and a "clear cache" action), not
 * just an always-on cache.
 */
data class ImageCachePolicy(
    val enabled: Boolean,
    val maxCacheBytes: Long? = null, // null = unbounded; evict oldest-by-digest when exceeded
)

interface ImageCache {
    suspend fun get(digest: String): String? // cached blob/layer path, if present
    /** [label] is the human-readable `reference:tag` the digest was pulled as — kept alongside the blob so a cache-management UI can show "debian:bookworm", not an opaque digest. */
    suspend fun put(digest: String, blobPath: String, label: String? = null)
    /** Every cached entry: digest, size on disk, and the [put]-time label when one was recorded. */
    suspend fun entries(): List<ImageCacheEntry>
    suspend fun evictToFit(policy: ImageCachePolicy)
    suspend fun clear()
}

data class ImageCacheEntry(val digest: String, val sizeBytes: Long, val label: String?)

interface RootfsPuller {
    suspend fun resolve(reference: String): RootfsImage

    /**
     * Pulls [image] via vendor/crane, consulting [cache] first when
     * [policy] has caching enabled — layers already on disk by digest are
     * reused rather than re-fetched from the registry.
     */
    suspend fun pullAndUnpack(
        image: RootfsImage,
        destinationPath: String,
        cache: ImageCache,
        policy: ImageCachePolicy,
    )
}
