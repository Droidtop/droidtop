package dev.droidtop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.view.Display

/**
 * Keeps droidtop's own second-screen surface attached to any secondary
 * display even when droidtop does NOT hold the Home role (owner,
 * 2026-09-27: "Our standard mode's second screen should still attach when
 * we're running other launcher intents"). Docs/SPEC.md 4c, "Attaching
 * without Home".
 *
 * Why this has to be a separate mechanism from the existing ones: Android
 * only places [dev.droidtop.display.SecondaryDisplayActivity] (droidtop's
 * `SECONDARY_HOME` activity) on a secondary display while droidtop IS the
 * platform's current default Home app -- that placement is the platform's
 * own SECONDARY_HOME policy, not something an app can opt into for
 * displays generally. When a person has chosen a different launcher (Nova,
 * Pixel Launcher, the stock AOSP one, ...), that OTHER app's own
 * SECONDARY_HOME activity (or the platform's bare fallback) gets placed
 * instead, and droidtop's real content never appears there at all -- which
 * is exactly `SecondaryDisplayLauncher`'s bare "floating apps button" the
 * rig kept photographing (docs/SPEC.md 4c, companion-fallback finding).
 *
 * A foreground `Service` holding its own `Presentation` sidesteps this
 * cleanly and within what the platform actually allows a normal,
 * non-privileged app to do:
 * - Starting an ACTIVITY on a secondary display from a background
 *   component is restricted since Android 10 (background-activity-launch
 *   policy; source.android.com/docs/core/display/multi_display/
 *   activity-launch confirms no blanket exemption exists for a background
 *   service, and this service never attempts it).
 * - A `Presentation` is not an activity launch: it is a `Dialog`-style
 *   window added directly through `WindowManager`, scoped to a `Display`
 *   context the service already legitimately holds (`android.software.
 *   presentation`, already declared and already how [SecondScreenPresentation]
 *   works today) -- not the `TYPE_APPLICATION_OVERLAY` mechanism
 *   `SYSTEM_ALERT_WINDOW` gates, and not subject to the activity-launch
 *   restriction at all. This is the same, standard pattern wireless-display
 *   and media-route-provider apps use to keep content on an external
 *   display independent of which app is foreground.
 * - No root: everything here is `DisplayManager`, `Presentation` and a
 *   plain foreground service (`FOREGROUND_SERVICE_SPECIAL_USE`, already
 *   declared for [DesktopSessionService]).
 *
 * Real, honest limit: droidtop registers no boot receiver (docs/SPEC.md
 * 2c, "Home goes to the default mode" section) and this service does not
 * add one, so nothing starts this service, or droidtop's process at all,
 * purely because Android booted while another app holds Home -- the
 * person has to open droidtop at least once (its one launcher icon, from
 * whichever launcher IS Home) before this can attach. From that point on
 * it persists as an ordinary foreground service (survives the opening
 * Activity closing, reattaches across display add/remove and across the
 * other launcher's own foreground/background changes) until Android kills
 * the process or the device reboots.
 *
 * Never double-covers a display already handled by an Activity-based
 * surface: it stands down entirely while droidtop holds Home (the
 * existing mechanisms already own that case) and backs off from any
 * specific display [dev.droidtop.display.SecondScreenOwnership] or
 * [dev.droidtop.display.SecondaryDisplayActivity.resumedDisplayId] says an
 * Activity already owns.
 */
class SecondScreenAttachService : Service() {

    private var presentation: SecondScreenPresentation? = null
    private var displayManager: DisplayManager? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reconcile()
        override fun onDisplayRemoved(displayId: Int) = reconcile()
        override fun onDisplayChanged(displayId: Int) = reconcile()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        val manager = getSystemService(DisplayManager::class.java)
        displayManager = manager
        manager.registerDisplayListener(listener, Handler(mainLooper))
        reconcile()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reconcile()
        return START_STICKY
    }

    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(listener)
        presentation?.dismiss()
        presentation = null
        super.onDestroy()
    }

    /**
     * The one decision this service makes, re-run on every display change
     * and every (re)start: is there a secondary display that needs
     * droidtop's own content, and does droidtop currently hold Home (in
     * which case this backs off and lets the existing mechanisms run).
     */
    private fun reconcile() {
        if (dev.droidtop.shell.standard.HomeRolePrefs.isDroidtopHome(this)) {
            // The platform's own SECONDARY_HOME placement already owns
            // this case; stacking a second window here would double-cover
            // the display SecondaryDisplayActivity is meant to hold.
            presentation?.dismiss()
            presentation = null
            stopSelf()
            return
        }
        val manager = displayManager ?: return
        val second = manager.displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY &&
                (it.flags and Display.FLAG_PRESENTATION) != 0
        }
        val owned = dev.droidtop.display.SecondScreenOwnership.activityOwnedDisplayId
        val idleOwned = dev.droidtop.display.SecondaryDisplayActivity.resumedDisplayId
        if (second == null || second.displayId == owned || second.displayId == idleOwned) {
            presentation?.dismiss()
            presentation = null
            return
        }
        if (presentation?.display?.displayId != second.displayId) {
            presentation?.dismiss()
            // Always Standard's own content here, never `currentMode()`'s
            // last-used mode -- see SecondScreenPresentation's own doc on
            // `forcedMode`. Nothing of Gaming's or Desktop's Activity is
            // running while this service is the one acting at all.
            presentation = SecondScreenPresentation(
                applicationContext,
                second,
                forcedMode = dev.droidtop.display.SecondaryDisplayContent.Mode.STANDARD,
            ).also { it.show() }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "droidtop_second_screen"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Second screen", NotificationManager.IMPORTANCE_MIN),
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("droidtop's second screen is active")
            .setContentText("Keeping the attached display on droidtop's own surface")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0xD802

        /** Started opportunistically wherever mode/Home state can change; a no-op (stops itself) whenever droidtop already holds Home. */
        fun ensureRunning(context: android.content.Context) {
            runCatching {
                context.startForegroundService(Intent(context, SecondScreenAttachService::class.java))
            }
        }
    }
}
