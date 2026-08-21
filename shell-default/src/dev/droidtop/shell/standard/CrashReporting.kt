package dev.droidtop.shell.standard

import android.content.Context
import com.zxy.recovery.callback.RecoveryCallback
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid

/**
 * Crash reporting to droidtop's own self-hosted Bugsink instance (a
 * Sentry-API-compatible server — see docs/SPEC.md). [Recovery][
 * com.zxy.recovery.core.Recovery] (inherited from the Murine Launcher
 * fork, wired in `LauncherApplication.onCreate`) still owns the on-device
 * "app crashed, restart?" recovery UI; this is just the reporting
 * transport, replacing Recovery's built-in "email the developer" button
 * (which pointed at Murine's own maintainer — droidtop's bugs are not
 * their problem, and their inbox was never the right place for them).
 */
object CrashReporting {
    /**
     * Empty until a real Bugsink DSN exists — the Sentry Android SDK
     * safely no-ops (doesn't crash, doesn't report anywhere) when
     * initialized with a blank DSN, so this is a real, safe default, not
     * a placeholder that needs removing before it works at all. Fill in
     * once the Bugsink instance (bugsink.oniimediaworks.com) has a
     * project + DSN.
     */
    private const val BUGSINK_DSN = ""

    fun init(context: Context) {
        SentryAndroid.init(context) { options ->
            options.dsn = BUGSINK_DSN
        }
    }
}

/**
 * Forwards Recovery's caught-crash callback into Sentry — this is the
 * actual reporting path, registered via `.callback(DroidtopRecoveryCallback())`
 * in `LauncherApplication.onCreate` instead of Recovery's `showDevEmail`.
 * [throwable] is the only one of [RecoveryCallback]'s four methods that
 * gives back the real exception object (the other three are
 * already-stringified fragments of it) — `Sentry.captureException` wants
 * the [Throwable] itself, so that's the only method this actually needs.
 *
 * UNVERIFIED against a real crash on a real device — no DSN exists to
 * report to yet ([CrashReporting.BUGSINK_DSN] is still blank), so nothing
 * has actually reached a live Bugsink instance through this path.
 */
class DroidtopRecoveryCallback : RecoveryCallback {
    override fun stackTrace(stackTrace: String) {}
    override fun cause(cause: String) {}
    override fun exception(throwExceptionType: String, throwClassName: String, throwMethodName: String, throwLineNumber: Int) {}

    override fun throwable(throwable: Throwable) {
        Sentry.captureException(throwable)
    }
}
