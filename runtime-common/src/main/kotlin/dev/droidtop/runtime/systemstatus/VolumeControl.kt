package dev.droidtop.runtime.systemstatus

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.droidtop.library.settings.UiMode
import dev.droidtop.library.settings.UiModeRefresh

/**
 * The one volume write path (docs/SPEC.md "The companion's tabs", Sound; Droidtop/tracker#414 slice C19): the Quick
 * Menu's slider, the companion's tile and steppers and its per-stream sliders all set volume here, and Kid's maximum
 * volume ([kidCapPercent], off by default) is applied here, so no caller can go past it. Hardware keys are clamped back
 * down by [watchKidCap] while Kid is on.
 */
object VolumeControl {
    /** The streams the companion's Sound card shows, with their names. */
    enum class Stream(val id: Int, val label: String) {
        MEDIA(AudioManager.STREAM_MUSIC, "Media"),
        RING(AudioManager.STREAM_RING, "Ring"),
        NOTIFICATION(AudioManager.STREAM_NOTIFICATION, "Notifications"),
        ALARM(AudioManager.STREAM_ALARM, "Alarms"),
        CALL(AudioManager.STREAM_VOICE_CALL, "Calls"),
    }

    private const val PREFS = "volume_control"
    private const val KEY_KID_CAP = "kid_cap_percent"

    /** Kid's maximum volume in percent of each stream's range; 0 is no cap (the default). */
    @Volatile
    var kidCapPercent: Int = 0
        private set

    fun load(context: Context) {
        kidCapPercent = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_KID_CAP, 0)
    }

    fun setKidCap(context: Context, percent: Int) {
        kidCapPercent = percent.coerceIn(0, 100)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_KID_CAP, kidCapPercent).apply()
    }

    /** The highest level a stream may be set to in [mode]: its maximum, or Kid's cap of it. Pure. */
    fun ceiling(max: Int, mode: UiMode, capPercent: Int): Int =
        if (mode == UiMode.KID && capPercent in 1..99) (max * capPercent / 100).coerceAtLeast(1) else max

    /** [value] as it may be written: within 0 and the ceiling. Pure. */
    fun clamp(value: Int, max: Int, mode: UiMode, capPercent: Int): Int = value.coerceIn(0, ceiling(max, mode, capPercent))

    fun max(context: Context, stream: Stream = Stream.MEDIA): Int =
        context.getSystemService(AudioManager::class.java)?.getStreamMaxVolume(stream.id) ?: 15

    fun get(context: Context, stream: Stream = Stream.MEDIA): Int =
        context.getSystemService(AudioManager::class.java)?.getStreamVolume(stream.id) ?: 0

    /** Sets [stream] to [value], clamped to Kid's cap while Kid is on. */
    fun set(context: Context, stream: Stream, value: Int) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(stream.id)
        runCatching { audio.setStreamVolume(stream.id, clamp(value, max, UiModeRefresh.mode.value, kidCapPercent), 0) }
    }

    private var observer: ContentObserver? = null

    /**
     * While Kid is on with a cap, a volume key that goes past it is turned back down. Android announces volume changes
     * on the system settings, so this listens there; registered once at start, it does nothing in other modes.
     */
    fun watchKidCap(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        Thread { load(app) }.apply { isDaemon = true }.start()
        val watcher = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (UiModeRefresh.mode.value != UiMode.KID || kidCapPercent !in 1..99) return
                Stream.entries.forEach { stream ->
                    val now = get(app, stream)
                    val ceiling = ceiling(max(app, stream), UiMode.KID, kidCapPercent)
                    if (now > ceiling) set(app, stream, ceiling)
                }
            }
        }
        runCatching { app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, watcher) }
        observer = watcher
    }
}
