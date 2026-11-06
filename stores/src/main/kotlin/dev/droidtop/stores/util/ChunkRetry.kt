package dev.droidtop.stores.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * The one retry around a store's chunk or file fetch (Droidtop/tracker#766). droidtop's download engine already
 * carries a fetch through dropped connections, with its own backoff and failure budget (docs/SPEC.md 12a, "One
 * download engine"), so a store does not retry what the engine retries. Its own attempt is for what the engine cannot
 * do: try another CDN (GOG), fetch fresh links (Epic), or fetch again a chunk that arrived damaged (a hash or size
 * that is not the manifest's). Hence few attempts: [attempts] at most, a short pause between them.
 */
internal object ChunkRetry {
    /** One more try after the first, by default: the engine has already retried the transfer itself. */
    const val ATTEMPTS = 2

    /** The pause before the second attempt; doubled before each later one. */
    const val FIRST_PAUSE_MS = 1_000L

    /** The pause before attempt [attempt] (0 for the first). Pure. */
    fun pauseBefore(attempt: Int): Long = if (attempt <= 0) 0L else FIRST_PAUSE_MS shl (attempt - 1).coerceAtMost(4)

    /**
     * Runs [fetch] (given the attempt number, from 0) until it succeeds, [attempts] times at most, while [isActive].
     * Returns the last failure when none succeeded; a stopped download fails with "Download cancelled".
     */
    suspend fun <T> run(
        tag: String,
        what: String,
        attempts: Int = ATTEMPTS,
        isActive: () -> Boolean = { true },
        fetch: suspend (attempt: Int) -> Result<T>,
    ): Result<T> {
        var last: Throwable? = null
        for (attempt in 0 until attempts.coerceAtLeast(1)) {
            if (!isActive()) return Result.failure(IllegalStateException("Download cancelled"))
            if (attempt > 0) {
                Timber.tag(tag).w("$what failed (attempt $attempt of $attempts): ${last?.message}; trying again")
                delay(pauseBefore(attempt))
            }
            val result = fetch(attempt)
            if (result.isSuccess) return result
            last = result.exceptionOrNull()
            if (last is CancellationException) throw last
        }
        Timber.tag(tag).e(last, "$what failed after $attempts attempts")
        return Result.failure(last ?: IllegalStateException("$what failed"))
    }
}
