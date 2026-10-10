package dev.droidtop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.droidtop.app.update.UpdateNow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "update now" trigger (docs/SPEC.md 10b, decision 2026-09-11):
 *
 *     adb shell am broadcast -a dev.droidtop.UPDATE_NOW -n dev.droidtop.app/.UpdateNowReceiver
 *
 * It pokes droidtop into checking the release feed immediately, whatever
 * the update schedule says, and installs the published build when it is
 * newer than the running one. It exists because the schedule is the wrong
 * instrument when a fix has just been published and the device is in front
 * of you -- and because the console is not a test rig: builds reach it
 * through droidtop's own updater, not through a reinstall.
 *
 * The class lives in the application package so the component name in that
 * command line is the short one people can actually type.
 *
 * Guard: the receiver is exported but declares android:permission
 * "android.permission.DUMP" in the manifest, which only the shell (what adb
 * runs as), root and the system hold and which no ordinary app can be
 * granted. That permission check is the whole guard. There is no sender-uid
 * re-check here on purpose: a BroadcastReceiver cannot learn who sent a
 * broadcast below API 34, and on API 34+ getSentFromUid() reports a sender
 * only when that sender opted in through BroadcastOptions.setShareIdentity,
 * which adb's `am broadcast` never does -- so a uid check would reject the
 * one caller this receiver exists for.
 *
 * The broadcast is held open (goAsync) only while the pass reads the feed and
 * hands the APK download to Android's DownloadManager, a few seconds, and never
 * past [UpdateNow.broadcastHoldMs]: while it is held droidtop's process is not
 * cached, so Android does not freeze it. The download itself runs in the
 * system's downloads provider, and droidtop is woken to install when it ends
 * (Droidtop/tracker#445). Holding the broadcast for the whole download ANRed;
 * a bare thread after it returned was frozen mid-download; and a foreground
 * service started from here is refused on Android 12+ (rig, Android 14).
 */
class UpdateNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateNow.ACTION) return
        Log.i(UpdateNow.TAG, "UPDATE_NOW received (sender holds android.permission.DUMP): checking the release feed now")
        val pending = goAsync()
        val released = AtomicBoolean(false)
        val release: () -> Unit = { if (released.compareAndSet(false, true)) pending.finish() }
        val foreground = (intent.flags and Intent.FLAG_RECEIVER_FOREGROUND) != 0
        Handler(Looper.getMainLooper()).postDelayed({ release() }, UpdateNow.broadcastHoldMs(foreground))
        UpdateNow.start(context, onHandedOff = release)
    }
}
