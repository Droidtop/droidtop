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

/**
 * [block], or [fallback] when it throws an [Exception], logged once under [tag] as "[what] failed" (Droidtop/tracker#766).
 * A cancellation is thrown on, never logged as a failure, and an [Error] (out of memory, a missing class) is not caught:
 * it is a bug or a dying process, not an answer to fold into an empty list. It replaced some forty hand-written copies
 * of `catch (CancellationException) { throw } catch (Throwable) { log; fallback }`. Inline, so the block may suspend.
 */
inline fun <T> guarded(tag: String, what: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // runCatching: a JVM unit test has no android.util.Log.
        runCatching { android.util.Log.w(tag, "$what failed: ${failure.message}", failure) }
        fallback
    }
