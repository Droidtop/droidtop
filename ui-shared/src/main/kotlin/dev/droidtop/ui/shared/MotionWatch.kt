package dev.droidtop.ui.shared

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings

/**
 * Keeps [Motion] told of Android's animator duration scale for the whole process, so every host that draws droidtop's
 * motion (Gaming, the companion, Standard's settings, the screen host) degrades to instant when the person turns on
 * "Remove animations" (Droidtop/tracker#643: "Respect Android's 'remove animations' setting: everything degrades to
 * instant"). One settings observer on a thread of its own, installed once by the application; the read never runs on
 * the main thread.
 */
object MotionWatch {
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val thread = HandlerThread("motion-watch").apply { start() }
        val handler = Handler(thread.looper)
        fun read() {
            val scale = runCatching { Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
            Motion.updateScale(scale)
        }
        handler.post {
            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = read()
                },
            )
            read()
        }
    }
}
