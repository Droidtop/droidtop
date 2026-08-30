package dev.droidtop.runtime.linux.root

/**
 * Shared `crane` CLI invocations used by more than one caller — one
 * implementation per registry operation, not a private copy per class
 * ([CraneRootfsPuller] and [CraneImageCatalogResolver] each carried their
 * own identical `crane digest` run before this existed).
 */
internal object CraneCli {
    /** Resolves [reference] (`registry/repo:tag`) to its immutable digest via `crane digest`. */
    suspend fun digest(binaryPath: String, reference: String): String {
        val result = PlainProcess.run(binaryPath, "digest", reference)
        check(result.succeeded) { "crane digest failed for $reference: ${result.stderr}" }
        return result.stdout.trim()
    }
}
