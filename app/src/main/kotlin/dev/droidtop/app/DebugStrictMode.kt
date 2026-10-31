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
 * Nothing is reported for the [ALLOWED] classes and methods, which block the main thread on purpose. Add a class there only
 * with the reason beside it.
 */
object DebugStrictMode {
    const val TAG = "droidtop.StrictMode"

    private val ALLOWED = listOf(
        // commit(), not apply(): the flag has to be on disk before the process is replaced.
        "dev.droidtop.app.update.UpdateRelaunch",
        // commit(): the crash count has to survive the crash that is about to happen.
        "dev.droidtop.library.diagnostics.CrashRecovery",
        // Two existence checks, first thing in Application.onCreate: a restore the person staged must be in place before
        // anything opens a database (Droidtop/tracker#625), so this one cannot move off the main thread.
        "dev.droidtop.app.settings.DroidtopBackup#finishPendingRestore",
        // One statfs, no write: whether storage is too full to start has to be known before the first screen is drawn
        // (docs/SPEC.md 10c, "Storage full", Droidtop/tracker#849).
        "dev.droidtop.runtime.StorageGuard",
    )

    /** An entry is a class (every method of it) or `class#method` (that method only). */
    private fun allows(entry: String, frame: StackTraceElement): Boolean {
        val className = entry.substringBefore('#')
        val method = entry.substringAfter('#', "")
        return frame.className.startsWith(className) && (method.isEmpty() || frame.methodName == method)
    }

    /** The frame of droidtop's own code a violation points at, or null when it is not ours or is allowed. */
    internal fun reportedFrame(stack: Array<StackTraceElement>): StackTraceElement? {
        val own = stack.firstOrNull { it.className.startsWith("dev.droidtop.") } ?: return null
        return if (ALLOWED.any { allows(it, own) }) null else own
    }

    fun install(app: Application) {
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val policy = StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
        if (Build.VERSION.SDK_INT >= 28) {
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "droidtop-strictmode").apply { isDaemon = true } }
            policy.penaltyListener(executor, StrictModeViolationListener())
        } else {
            // No way to tell ours from a library's before API 28: everything is logged.
            policy.penaltyLog()
        }
        StrictMode.setThreadPolicy(policy.build())
    }
}

/**
 * The listener as a class of its own because its interface is Android 9's and droidtop's minSdk is 26: built only under
 * the SDK_INT >= 28 check in [DebugStrictMode.install] (build-scripts/class_load_api_allowlist.txt). A lambda there is
 * compiled into a class that implements the interface, which the class-load API gate refuses.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
internal class StrictModeViolationListener : StrictMode.OnThreadViolationListener {
    override fun onThreadViolation(v: android.os.strictmode.Violation) {
        DebugStrictMode.reportedFrame(v.stackTrace)?.let { Log.w(DebugStrictMode.TAG, "${v.javaClass.simpleName} on the main thread at $it") }
    }
}
