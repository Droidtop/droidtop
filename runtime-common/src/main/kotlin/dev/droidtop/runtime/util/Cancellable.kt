package dev.droidtop.runtime.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for a block that suspends: a [CancellationException] is thrown on, never caught as the block's failure
 * (Droidtop/tracker#766). The plain one catches it too, so a cancelled sync logged "no answer" and carried on with its
 * next step instead of stopping. Inline, so the block may call suspend functions from a coroutine.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
