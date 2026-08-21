package dev.droidtop.runtime.linux.root

import android.content.Context
import dev.droidtop.runtime.ImageCatalogResolver
import dev.droidtop.runtime.KnownImageRepository
import dev.droidtop.runtime.ResolvedImage

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
 * UNVERIFIED against a real network path in this environment — `crane ls`
 * and `crane digest` both work standalone (same confirmation as
 * [CraneRootfsPuller]'s own doc comment), but this class specifically
 * (list-then-resolve against one of [KnownImageRepository]'s real
 * SIBLING-role repositories, e.g. `library/alpine`) hasn't been run
 * end-to-end yet.
 */
class CraneImageCatalogResolver(private val context: Context) : ImageCatalogResolver {
    private val binaryPath: String by lazy { CraneBinary.ensureExtracted(context) }

    override suspend fun listTags(repository: KnownImageRepository): List<String> {
        val reference = "${repository.registry}/${repository.repository}"
        val result = PlainProcess.run(binaryPath, "ls", reference)
        check(result.succeeded) { "crane ls failed for $reference: ${result.stderr}" }
        return result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    override suspend fun resolve(repository: KnownImageRepository, tag: String): ResolvedImage {
        val reference = "${repository.registry}/${repository.repository}:$tag"
        val result = PlainProcess.run(binaryPath, "digest", reference)
        check(result.succeeded) { "crane digest failed for $reference: ${result.stderr}" }
        return ResolvedImage(repository = repository, tag = tag, digest = result.stdout.trim())
    }
}
