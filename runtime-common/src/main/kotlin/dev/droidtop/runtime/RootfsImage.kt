package dev.droidtop.runtime

/**
 * A container's root filesystem source, expressed as an OCI image reference
 * — the same addressing scheme `docker pull`/`podman pull` use — rather than
 * a bespoke tarball format. Pulled and unpacked on-device via vendor/crane
 * (from vendor/go-containerregistry): no Docker daemon involved, just an
 * OCI registry client that fetches layer blobs.
 *
 * This means the primary container's base image (a minimal distro + the
 * vendor/sway build) and any sibling container's base image (Ubuntu,
 * Debian, Alpine, Arch — whatever a user wants their "distrobox" to be) are
 * both just image references, pullable from Docker Hub, GHCR, or a private
 * registry, and stored by digest ([OciImageStore]) so re-creating a
 * container doesn't re-download anything unchanged.
 */
data class RootfsImage(
    val reference: String, // e.g. "docker.io/library/debian:bookworm" or a private registry ref
    val digest: String? = null, // pin by digest once resolved, for reproducible/cached pulls
)

/**
 * User-facing choice, not just an implementation detail: keeping pulled
 * images around means re-creating/duplicating a container (e.g. spinning up
 * a second Debian sibling) doesn't re-download anything, at the cost of
 * on-device storage. [OciImageStore] holds them; with [enabled] false an
 * image is removed from it once its rootfs is written.
 */
data class ImageCachePolicy(
    val enabled: Boolean,
    val maxCacheBytes: Long? = null, // null = unbounded; least recently used images are evicted when exceeded
) {
    companion object {
        /** docs/SPEC.md §3, "Storage is checked before it is spent": on, capped at 2 GB. */
        val DEFAULT = ImageCachePolicy(enabled = true, maxCacheBytes = 2L * 1024 * 1024 * 1024)
    }
}

interface RootfsPuller {
    suspend fun resolve(reference: String): RootfsImage

    /**
     * Makes [destinationPath] a rootfs of [image], pulling it into the
     * image store first unless it is already there.
     */
    suspend fun pullAndUnpack(image: RootfsImage, destinationPath: String, policy: ImageCachePolicy)
}
