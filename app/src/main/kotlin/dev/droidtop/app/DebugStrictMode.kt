package dev.droidtop.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode
import android.util.Log
import java.util.concurrent.Executors

/**
 * The rule that no file, database, image or network work runs on the main thread (docs/SPEC.md 10c, "Main-thread
 * work"), enforced in debuggable builds (Droidtop/tracker#729): StrictMode watches the main thread for disk reads,
 * disk writes and network, and a violation that comes from droidtop's own code is logged under [TAG] with the frame
 * of ours it points at. A rig run fails on a [TAG] line, so a regression shows up there and not as a handheld that
 * feels slow. Code of the libraries droidtop is built on is not reported: it is not ours to fix here.
 *
 * Nothing is reported for the [ALLOWED] classes, which block the main thread on purpose. Add a class there only
 * with the reason beside it.
 */
object DebugStrictMode {
    const val TAG = "droidtop.StrictMode"

    private val ALLOWED = listOf(
        // commit(), not apply(): the flag has to be on disk before the process is replaced.
        "dev.droidtop.app.update.UpdateRelaunch",
        // commit(): the crash count has to survive the crash that is about to happen.
        "dev.droidtop.library.diagnostics.CrashRecovery",
    )

    /** The frame of droidtop's own code a violation points at, or null when it is not ours or is allowed. */
    internal fun reportedFrame(stack: Array<StackTraceElement>): StackTraceElement? {
        val own = stack.firstOrNull { it.className.startsWith("dev.droidtop.") } ?: return null
        return if (ALLOWED.any { own.className.startsWith(it) }) null else own
    }

    fun install(app: Application) {
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val policy = StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
        if (Build.VERSION.SDK_INT >= 28) {
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "droidtop-strictmode").apply { isDaemon = true } }
            policy.penaltyListener(executor) { violation ->
                reportedFrame(violation.stackTrace)?.let { Log.w(TAG, "${violation.javaClass.simpleName} on the main thread at $it") }
            }
        } else {
            // No way to tell ours from a library's before API 28: everything is logged.
            policy.penaltyLog()
        }
        StrictMode.setThreadPolicy(policy.build())
    }
}
