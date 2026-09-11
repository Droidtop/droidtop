package dev.droidtop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import dev.droidtop.app.update.UpdateNow

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
 * Guard: the receiver is exported but requires android.permission.DUMP of
 * its sender, which only shell, root and the system hold, and it re-checks
 * the sending uid where Android exposes it (API 34+). Everything else is
 * ignored with a log line.
 */
class UpdateNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateNow.ACTION) return
        val ownUid = Process.myUid()
        val senderUid = if (Build.VERSION.SDK_INT >= 34) sendingUid else ownUid
        if (!UpdateNow.isTrustedCaller(senderUid, ownUid)) {
            Log.w(UpdateNow.TAG, "ignored UPDATE_NOW from uid " + senderUid + " (shell, root or droidtop only)")
            return
        }
        Log.i(UpdateNow.TAG, "UPDATE_NOW from uid " + senderUid + ": checking the release feed now")
        val pending = goAsync()
        val application = context.applicationContext
        Thread {
            try {
                UpdateNow.runNow(application)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
