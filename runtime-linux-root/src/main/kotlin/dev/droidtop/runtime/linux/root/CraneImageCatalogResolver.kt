package dev.droidtop.runtime.linux.root

import android.content.Context
import dev.droidtop.runtime.ImageCatalogResolver
import dev.droidtop.runtime.KnownImageRepository
import dev.droidtop.runtime.ResolvedImage
import java.io.File

/**
 * [ImageCatalogResolver] backed by vendor/crane (see [CraneBinary]/
 * [CraneRootfsPuller] — same binary, same "plain network call" privilege
 * level, no root needed). This is where droidtop's "populate the catalog
 * at runtime, don't prepopulate it" model (docs/SPEC.md §3a) actually
 * talks to a real registry:
 *
 *  - `crane ls <repo>` lists every tag currently published — this is
 *    droidtop's *only* source of "what versions exist," replacing what an
 *    earlier version of this catalog got wrong: baking specific version
 *    strings (like "bookworm" or "3.20") into a bundled manifest that
 *    would go stale the moment a distro cut a new release.
 *  - `crane digest <repo>:<tag>` resolves one tag to its immutable digest
 *    — the same operation [CraneRootfsPuller.resolve] already performs for
 *    pulls, reused here so [ResolvedImage.toRootfsImage] produces the
 *    exact reference/digest shape [CraneRootfsPuller.pullAndUnpack] expects.
 *
 * On-device network path confirmed real (2026-08-30, RP5): the bundled
 * cgo-built crane resolves DNS through bionic and `crane ls
 * docker.io/library/alpine` returns real tags when run directly — see
 * docs/SPEC.md §10a for the full resolver story (and the stale-extraction
 * bug that hid the fix for a build). This class's own list-then-resolve
 * flow through a full desktop-session start is the remaining end-to-end
 * verification.
 */
class CraneImageCatalogResolver(
    private val context: Context,
    /** How long a `crane ls` tag listing stays fresh on disk. Tag lists move slowly (a distro cuts releases weekly at most), so an hour is conservative. */
    private val listTagsTtlMs: Long = 60L * 60L * 1000L,
) : ImageCatalogResolver {
    private val binaryPath: String by lazy { CraneBinary.ensureExtracted(context) }

    override suspend fun listTags(repository: KnownImageRepository): List<String> {
        val reference = "${repository.registry}/${repository.repository}"
        // Disk-backed TTL cache: every catalog open used to re-run
        // `crane ls` for every repository, a real registry round-trip per
        // row. Freshness is the file's own mtime; a stale (or absent)
        // entry re-lists, and a stale entry is still SERVED when the
        // network call fails -- a catalog that browsed fine an hour ago
        // shouldn't go blank the moment the device is offline. `digest`
        // resolution below stays uncached: it runs once per actual
        // selection, and pinning it must reflect the registry right now.
        val cacheFile = File(File(context.cacheDir, "catalog-tags"), reference.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt")
        val cachedTags = cacheFile.takeIf { it.exists() }
            ?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (cachedTags != null && System.currentTimeMillis() - cacheFile.lastModified() < listTagsTtlMs) {
            return cachedTags
        }
        val result = PlainProcess.run(binaryPath, "ls", reference)
        if (!result.succeeded && cachedTags != null) return cachedTags
        check(result.succeeded) { "crane ls failed for $reference: ${result.stderr}" }
        val tags = result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(tags.joinToString("\n"))
        }
        return tags
    }

    override suspend fun resolve(repository: KnownImageRepository, tag: String): ResolvedImage {
        val reference = "${repository.registry}/${repository.repository}:$tag"
        return ResolvedImage(repository = repository, tag = tag, digest = CraneCli.digest(binaryPath, reference))
    }
}
