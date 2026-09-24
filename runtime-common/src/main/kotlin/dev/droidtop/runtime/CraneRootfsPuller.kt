package dev.droidtop.runtime

/**
 * The half of turning an image into a rootfs that depends on who owns the
 * tree. Pulling and flattening are the same for every backend
 * ([CraneRootfsPuller], [OciFlattener]); writing the files is not:
 * droidspaces needs a root-owned tree with real ownership (written by
 * root's `tar`), while proot needs a tree the app itself owns and can later
 * remove (written in-process, with proot faking root ownership at run time).
 *
 * Every method is handed the destination directory the puller chose;
 * implementations must never follow a symlink out of it.
 */
interface RootfsUnpacker {
    /** Whether [destinationPath] already holds a complete extraction of [digest] (see [markComplete]). */
    suspend fun isCurrent(destinationPath: String, digest: String): Boolean

    /** Removes whatever is at [destinationPath]: a partial extraction, or a different image. */
    suspend fun wipe(destinationPath: String)

    /**
     * Writes [content] into the empty [destinationPath], creating it. The
     * entries [content] feeds are already clean ([OciFlattener]); what
     * each implementation adds is only how they reach the filesystem.
     */
    suspend fun extract(content: RootfsContent, destinationPath: String)

    /** Records that [destinationPath] holds [digest]. Called last, so a failure never leaves a tree claiming to be complete. */
    suspend fun markComplete(destinationPath: String, digest: String)
}

/** A flattened image, fed entry by entry to whichever sink writes it. Blocking: call it off the main thread. */
fun interface RootfsContent {
    fun writeTo(sink: RootfsEntrySink): OciFlattener.Result
}

/**
 * [RootfsPuller] backed by [Crane] and [OciImageStore], shared by both
 * container backends, which differ only in their [unpacker]:
 *
 *  - `crane digest <reference>` resolves a tag to its immutable digest
 *    ([resolve]), the key the store is kept by, since a tag like
 *    `:bookworm` can move but a digest can't.
 *  - `crane pull --format=oci` puts the image into the store (an OCI image
 *    layout), layers shared with images already there not downloaded again.
 *  - [OciFlattener] applies the layers and whiteouts and checks every
 *    entry, and the [unpacker] writes what it emits. Nothing is staged on
 *    disk between the store and the rootfs.
 *
 * All of it runs as the app, but for droidspaces' final write: registry
 * calls into app-private storage need no privilege on either backend.
 */
class CraneRootfsPuller(
    private val binaryPath: () -> String,
    private val store: OciImageStore,
    private val unpacker: RootfsUnpacker,
) : RootfsPuller {
    override suspend fun resolve(reference: String): RootfsImage =
        RootfsImage(reference = reference, digest = Crane.digest(binaryPath(), reference))

    override suspend fun pullAndUnpack(image: RootfsImage, destinationPath: String, policy: ImageCachePolicy) {
        val resolved = if (image.digest != null) image else resolve(image.reference)
        val digest = resolved.digest!!

        // Idempotence + no image mixing: a destination already extracted
        // from THIS digest is left alone (fast session restarts); one
        // holding anything else -- a different image, or a partial/failed
        // earlier attempt (the real first-run case: an alpine:2.6 rootfs
        // left behind by a failed create) -- is wiped first, since
        // extracting over an existing tree silently merges two images into
        // one broken rootfs. Checked before pulling, so a restart never
        // touches the registry.
        if (unpacker.isCurrent(destinationPath, digest)) return

        val stored = store.pull(resolved)
        unpacker.wipe(destinationPath)
        unpacker.extract(RootfsContent { sink -> OciFlattener.flatten(store, stored, sink) }, destinationPath)
        unpacker.markComplete(destinationPath, digest)

        if (policy.enabled) store.evictToFit(policy, keep = digest) else store.remove(digest)
    }

    companion object {
        /** Name of the marker file both unpackers write at the top of a finished rootfs. */
        const val DIGEST_MARKER = ".droidtop-image-digest"
    }
}
