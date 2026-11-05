package dev.droidtop.runtime.windows

import android.content.Context
import android.os.SystemClock
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import dev.droidtop.library.format.ValueFormat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * "Prepare for first launch" (docs/SPEC.md 9; Droidtop/tracker#866, option 2): the game started out of sight and silent
 * so its translation cache ([TranslationCache]) holds its start-up code before the person's first real start.
 *
 * The session is the one a real launch makes ([WineXSession], the same prefix and launch environment), with an
 * [XServer] that has no renderer (nothing on screen) and no audio server. It runs until [BUDGET_MS] has passed, or the
 * cache has grown and then stopped growing for [QUIET_MS], or the game ends by itself, and is then stopped the way
 * Stop stops a game. Cancelling the job stops it too.
 */
internal object WinePrecache {
    const val BUDGET_MS = 3 * 60_000L
    const val QUIET_MS = 30_000L
    private const val POLL_MS = 5_000L

    suspend fun run(
        context: Context,
        plan: WindowsLaunchPlan.Ready,
        entryId: String,
        executable: File,
        report: (percent: Int, statusLine: String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val before = TranslationCache.size(context, entryId)
        val xServer = XServer(ScreenInfo(plan.container.screenSize), false)
        val session = WineXSession(context, plan.container, plan.target, plan.workingDir, xServer, plan.arguments, silent = true)
        val ended = CompletableDeferred<Int>()
        report(0, "Starting the game out of sight")
        session.start { code, _ -> ended.complete(code) }
        val started = SystemClock.elapsedRealtime()
        var size = before
        var lastGrowth = started
        var why = "the time it is given ran out"
        try {
            while (true) {
                ensureActive()
                val now = SystemClock.elapsedRealtime()
                val current = TranslationCache.size(context, entryId)
                if (current > size) {
                    size = current
                    lastGrowth = now
                }
                val elapsed = now - started
                report(progress(elapsed), "Translated ${ValueFormat.size(size)} so far")
                when {
                    ended.isCompleted -> { why = "the game ended by itself"; break }
                    stopNow(elapsed, size > before, now - lastGrowth) -> {
                        if (elapsed < BUDGET_MS) why = "the cache stopped growing"
                        break
                    }
                }
                delay(POLL_MS)
            }
        } finally {
            session.stop()
        }
        WineOptions.recordPrepared(context, entryId, size, executable)
        "Prepared: ${ValueFormat.size(size)} of translated code (${ValueFormat.size((size - before).coerceAtLeast(0))} new); stopped because $why."
    }

    /** Whether the run has done what it can: out of time, or the cache grew and has since been quiet for [QUIET_MS]. Pure. */
    internal fun stopNow(elapsedMs: Long, grew: Boolean, quietMs: Long): Boolean =
        elapsedMs >= BUDGET_MS || (grew && quietMs >= QUIET_MS)

    /** Elapsed time as a share of [BUDGET_MS], 0 to 99 until the run ends. Pure. */
    internal fun progress(elapsedMs: Long): Int = ((elapsedMs * 100) / BUDGET_MS).toInt().coerceIn(0, 99)
}
