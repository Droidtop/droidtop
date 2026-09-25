package dev.droidtop.shell.standard

import android.content.Context
import io.sentry.android.core.SentryAndroid

/**
 * Crash reporting to droidtop's own self-hosted Bugsink instance (a
 * Sentry-API-compatible server — see docs/SPEC.md). The SDK installs its
 * own uncaught-exception handler, which is the reporting path. The Murine
 * fork's Recovery crash screen that used to sit in front of it is gone
 * (see LauncherApplication.onCreate).
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
