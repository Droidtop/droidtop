package dev.droidtop.runtime

import android.content.Context
import java.io.File

/**
 * The half of turning an image into a rootfs that depends on who owns the
 * tree. Pulling is the same for every backend ([CraneRootfsPuller]);
 * writing the files is not: droidspaces needs a root-owned tree with real
 * ownership (extracted by root), while proot needs a tree the app itself
 * owns and can later remove (extracted in-process, with proot faking root
 * ownership at run time).
 *
 * Every method is handed the destination directory the puller chose;
 * implementations must never follow a symlink out of it.
 */
interface RootfsUnpacker {
    /** Whether [destinationPath] already holds a complete extraction of [digest] (see [markComplete]). */
    suspend fun isCurrent(destinationPath: String, digest: String): Boolean

    /** Removes whatever is at [destinationPath]: a partial extraction, or a different image. */
    suspend fun wipe(destinationPath: String)

    /** Extracts the flat filesystem tarball [tarPath] into the empty [destinationPath], creating it. */
    suspend fun extract(tarPath: String, destinationPath: String)

    /** Records that [destinationPath] holds [digest]. Called last, so a failure never leaves a tree claiming to be complete. */
    suspend fun markComplete(destinationPath: String, digest: String)
}

/**
 * [RootfsPuller] backed by [Crane], shared by both container backends,
 * which differ only in their [unpacker]. Two crane subcommands cover
 * everything:
 *
 *  - `crane digest <reference>` resolves a tag to its immutable digest
 *    ([resolve]), the thing [ImageCache] keys on, since a tag like
 *    `:bookworm` can move but a digest can't.
 *  - `crane export <reference> <tarball>` pulls every layer and flattens
 *    them into one filesystem tarball (like `docker export`): exactly the
 *    "get me a rootfs" shape this needs, with whiteouts already applied.
 *
 * Both run as the app itself: registry calls writing into app-private
 * storage need no privilege on either backend.
 */
class CraneRootfsPuller(
    private val context: Context,
    private val unpacker: RootfsUnpacker,
) : RootfsPuller {
    private val binaryPath: String by lazy { Crane.binaryPath(context) }

    override suspend fun resolve(reference: String): RootfsImage =
        RootfsImage(reference = reference, digest = Crane.digest(binaryPath, reference))

    override suspend fun pullAndUnpack(
        image: RootfsImage,
        destinationPath: String,
        cache: ImageCache,
        policy: ImageCachePolicy,
    ) {
        val digest = image.digest ?: resolve(image.reference).digest!!

        // Idempotence + no image mixing: a destination already extracted
        // from THIS digest is left alone (fast session restarts); one
        // holding anything else -- a different image, or a partial/failed
        // earlier attempt (the real first-run case: an alpine:2.6 rootfs
        // left behind by a failed create) -- is wiped first, since
        // extracting over an existing tree silently merges two images into
        // one broken rootfs. Checked before pulling, so a restart never
        // touches the registry.
        if (unpacker.isCurrent(destinationPath, digest)) return

        val cachedTarPath = if (policy.enabled) cache.get(digest) else null
        val tarPath = cachedTarPath ?: run {
            // Always pulls into our own scratch location, never into the
            // cache's internal storage directly -- ImageCache.put() owns
            // where a cached blob actually lives, not this class.
            val scratchPath = File(context.cacheDir, "rootfs-pull-$digest.tar").absolutePath
            val pullResult = ProcessRunner.run(listOf(binaryPath, "export", "${image.reference}@$digest", scratchPath))
            check(pullResult.succeeded) {
                "crane export failed for ${image.reference}@$digest: ${pullResult.stderr}"
            }
            if (policy.enabled) {
                cache.put(digest, scratchPath, label = image.reference)
                cache.get(digest) ?: error("ImageCache.put($digest, ...) didn't make it available via get()")
            } else {
                scratchPath
            }
        }

        unpacker.wipe(destinationPath)
        unpacker.extract(tarPath, destinationPath)
        unpacker.markComplete(destinationPath, digest)

        if (!policy.enabled) {
            File(tarPath).delete()
        }
    }

    companion object {
        /** Name of the marker file both unpackers write at the top of a finished rootfs. */
        const val DIGEST_MARKER = ".droidtop-image-digest"
    }
}
