package dev.droidtop.runtime.linux.root

import android.content.Context
import dev.droidtop.runtime.ImageCache
import dev.droidtop.runtime.ImageCachePolicy
import dev.droidtop.runtime.RootfsImage
import dev.droidtop.runtime.RootfsPuller
import java.io.File

/**
 * [RootfsPuller] backed by vendor/crane (from vendor/go-containerregistry,
 * bundled as an APK asset — see [CraneBinary]): no Docker daemon, just an
 * OCI registry client. Two crane subcommands cover everything this
 * interface needs:
 *
 *  - `crane digest <reference>` resolves a tag to its immutable digest
 *    ([resolve]) — the thing [ImageCache] actually keys on, since a tag
 *    like `:bookworm` can move but a digest can't.
 *  - `crane export <reference> <tarball>` pulls every layer and flattens
 *    them into one filesystem tarball (like `docker export`) — exactly the
 *    "get me a rootfs" shape this needs, and simpler than manually walking
 *    a manifest's layer list and extracting each one in order.
 *
 * Both are plain network/app-storage operations ([PlainProcess], no root)
 * — extracting the resulting tarball into a container's actual rootfs
 * directory uses [RootProcess] instead, matching [DroidSpacesRuntime]'s own
 * privilege level for anything under its rootfs/config trees.
 *
 * UNVERIFIED against a real device or a real container boot: `crane`
 * cross-compiles and runs standalone (confirmed locally — `crane version`
 * and a real `crane digest`/`crane export` pull against a public image both
 * worked against real network access), but nothing has pulled an image
 * *for* a real droidspaces container yet — the recommended-image catalog's
 * (runtime-common's `image-catalog.json`, see docs/SPEC.md §3a) PRIMARY
 * entries are still placeholder references, since no primary image with a
 * compositor pre-installed has been published anywhere yet.
 */
class CraneRootfsPuller(private val context: Context) : RootfsPuller {
    private val binaryPath: String by lazy { CraneBinary.ensureExtracted(context) }

    override suspend fun resolve(reference: String): RootfsImage {
        val result = PlainProcess.run(binaryPath, "digest", reference)
        check(result.succeeded) { "crane digest failed for $reference: ${result.stderr}" }
        return RootfsImage(reference = reference, digest = result.stdout.trim())
    }

    override suspend fun pullAndUnpack(
        image: RootfsImage,
        destinationPath: String,
        cache: ImageCache,
        policy: ImageCachePolicy,
    ) {
        val digest = image.digest ?: resolve(image.reference).digest!!

        val cachedTarPath = if (policy.enabled) cache.get(digest) else null
        val tarPath = cachedTarPath ?: run {
            // Always pulls into our own scratch location, never into the
            // cache's internal storage directly — ImageCache.put() owns
            // where a cached blob actually lives, not this class.
            val scratchPath = File(context.cacheDir, "rootfs-pull-$digest.tar").absolutePath
            val pullResult = PlainProcess.run(binaryPath, "export", "${image.reference}@$digest", scratchPath)
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

        // Idempotence + no image mixing: a destination already extracted
        // from THIS digest is left alone (fast session restarts); one
        // holding anything else — a different image, or a partial/failed
        // earlier attempt (the real first-run case: an alpine:2.6 rootfs
        // left behind by a failed create) — is wiped first, since tar
        // over an existing tree silently merges two images into one
        // broken rootfs. The marker is written LAST, so a wipe-then-fail
        // never leaves a tree claiming to be complete.
        val digestMarker = "$destinationPath/.droidtop-image-digest"
        val existing = RootProcess.run("cat", digestMarker)
        if (existing.succeeded && existing.stdout.trim() == digest) return
        RootProcess.run("rm", "-rf", destinationPath)

        val mkdirResult = RootProcess.run("mkdir", "-p", destinationPath)
        check(mkdirResult.succeeded) { "mkdir -p $destinationPath failed: ${mkdirResult.stderr}" }

        val extractResult = RootProcess.run("tar", "-xf", tarPath, "-C", destinationPath)
        check(extractResult.succeeded) {
            "Extracting $tarPath into $destinationPath failed: ${extractResult.stderr}"
        }
        val markResult = RootProcess.run("sh", "-c", "printf %s '$digest' > '$digestMarker'")
        check(markResult.succeeded) { "Writing $digestMarker failed: ${markResult.stderr}" }

        if (!policy.enabled) {
            File(tarPath).delete()
        }
    }
}
