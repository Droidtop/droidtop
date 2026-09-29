package dev.droidtop.library.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.droidtop.library.ScanLog
import dev.droidtop.library.settings.Modes
import dev.droidtop.library.theme.ThemeSafeMode
import java.io.File
import java.util.Date

/**
 * Crash notes and crash-loop safe mode (docs/SPEC.md 10c), one owner for
 * both: the uncaught-exception handler that writes the note, and the single
 * persisted counter that startup consults to decide between the themed
 * Gaming shell, its unthemed fallback and Global settings. Local only:
 * nothing here sends anything anywhere.
 */
object CrashRecovery {
    /** The window a crash counts as part of a loop, and how long a start must live to clear the counter. */
    const val WINDOW_MS = 60_000L

    /** Crashes within [WINDOW_MS] of starting that turn the themed Gaming shell off. */
    const val SAFE_MODE_AT = 2

    /** Crashes within [WINDOW_MS] of starting that send the next start to Global settings. */
    const val SETTINGS_AT = 3

    const val KEEP_NOTES = 10
    private const val MAX_TRACE_CHARS = 16 * 1024
    private const val SCAN_LOG_LINES = 100

    private const val PREFS = "droidtop_crash_state"
    private const val KEY_COUNT = "early_crashes"
    private const val NOTE_PREFIX = "crash-"

    @Volatile
    private var lastScreen: String = "no screen yet"

    /** The counter after a crash that happened [uptimeMs] after the process started: a crash of a process that lived a whole window is not part of a loop. */
    fun countAfterCrash(previous: Int, uptimeMs: Long): Int = if (uptimeMs >= WINDOW_MS) 0 else previous + 1

    fun safeModeFor(count: Int): Boolean = count >= SAFE_MODE_AT

    fun settingsFor(count: Int): Boolean = count >= SETTINGS_AT

    /** Keeps the [keep] newest of [names] (crash notes, named by their epoch); answers the ones to delete. */
    fun notesToDelete(names: List<String>, keep: Int = KEEP_NOTES): List<String> =
        names.filter { it.startsWith(NOTE_PREFIX) && it.endsWith(".txt") }
            .sortedByDescending { it.removePrefix(NOTE_PREFIX).removeSuffix(".txt").toLongOrNull() ?: 0L }
            .drop(keep)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called once from the Application, after [ScanLog.install]. */
    fun install(app: Application) {
        val started = SystemClock.elapsedRealtime()
        val stored = prefs(app).getInt(KEY_COUNT, 0)
        if (safeModeFor(stored)) ThemeSafeMode.set(true)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                lastScreen = activity.javaClass.name
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // A start that lives a whole window clears the counter.
        Handler(Looper.getMainLooper()).postDelayed({ prefs(app).edit().putInt(KEY_COUNT, 0).apply() }, WINDOW_MS)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Synchronous, before anything else: the process is about to die.
            runCatching { writeNote(app, thread, error) }
            runCatching {
                val next = countAfterCrash(prefs(app).getInt(KEY_COUNT, 0), SystemClock.elapsedRealtime() - started)
                prefs(app).edit().putInt(KEY_COUNT, next).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * A third crash in a window starts the app on Global settings rather than in
     * a shell. Answers true once for it and backs the counter off to the safe
     * mode level, so the start after that is the unthemed Gaming shell and the
     * person is not held in settings by every launch.
     */
    fun consumeSettingsRoute(context: Context): Boolean {
        if (!settingsFor(prefs(context).getInt(KEY_COUNT, 0))) return false
        prefs(context).edit().putInt(KEY_COUNT, SAFE_MODE_AT).apply()
        return true
    }

    /** The banner's action: draw the theme again. The stored theme choice was never changed. */
    fun retryTheme(context: Context) {
        prefs(context).edit().putInt(KEY_COUNT, 0).apply()
        ThemeSafeMode.set(false)
    }

    private fun writeNote(context: Context, thread: Thread, error: Throwable) {
        val dir = ScanLog.logsDir(context).apply { mkdirs() }
        val epoch = System.currentTimeMillis() / 1000
        val trace = android.util.Log.getStackTraceString(error).take(MAX_TRACE_CHARS)
        val mode = runCatching { Modes.lastMode(context) }.getOrDefault("unknown")
        val build = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown build"
        val scan = runCatching {
            File(dir, "scan.log").readLines().takeLast(SCAN_LOG_LINES).joinToString("\n")
        }.getOrDefault("(no scan.log)")
        File(dir, "$NOTE_PREFIX$epoch.txt").writeText(
            buildString {
                appendLine("droidtop $build crashed at ${Date()}")
                appendLine("mode: $mode")
                appendLine("screen: $lastScreen")
                appendLine("thread: ${thread.name}")
                appendLine()
                appendLine(trace)
                appendLine()
                appendLine("--- last $SCAN_LOG_LINES lines of scan.log ---")
                appendLine(scan)
            },
        )
        notesToDelete(dir.list()?.toList().orEmpty()).forEach { File(dir, it).delete() }
    }
}
