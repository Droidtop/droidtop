package dev.droidtop.runtime

/**
 * Which kept images go when the image cache is over its limit (docs/SPEC.md 3, "Storage is checked before it is
 * spent"; Droidtop/tracker#911): the pure half of [OciImageStore.evictToFit], so the choice is tested without a
 * store on disk.
 *
 * Images are given least recently used first, as the store keeps them. Each is taken in turn until what stays on
 * disk fits, skipping the protected ones: the image the pull just used and every image an in-progress create is
 * using ([OciImageStore.using]). A layer two images share is counted once and frees nothing until both are gone,
 * so removing an image is credited only with the blobs no remaining image reaches.
 */
object ImageCacheEviction {
    /** One kept image: the digest it was pulled by, and every blob it reaches (manifest, config, layers). */
    data class Kept(val digest: String, val blobs: Set<String>)

    /**
     * The indexes of [images] to remove, oldest-used first, so the blobs left fit [limit]. [blobBytes] is each blob's
     * size on disk; a blob it does not name counts as nothing. Empty when they already fit. When the protected images
     * alone are over it, every other image is chosen and the cache stays over its limit rather than break a create.
     */
    fun victims(images: List<Kept>, blobBytes: Map<String, Long>, limit: Long, protected: Set<String>): List<Int> {
        val users = HashMap<String, Int>()
        images.forEach { image -> image.blobs.forEach { users.merge(it, 1, Int::plus) } }
        var total = users.keys.sumOf { blobBytes[it] ?: 0L }
        val chosen = mutableListOf<Int>()
        for ((index, image) in images.withIndex()) {
            if (total <= limit) break
            if (image.digest in protected) continue
            chosen += index
            for (blob in image.blobs) {
                val left = users.getValue(blob) - 1
                users[blob] = left
                if (left == 0) total -= blobBytes[blob] ?: 0L
            }
        }
        return chosen
    }
}
