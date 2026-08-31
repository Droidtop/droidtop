package dev.droidtop.runtime.systemstatus

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The live notification list every droidtop surface reads — fed by
 * `:app`'s `DroidtopNotificationListener` (a real
 * NotificationListenerService, the same mechanism any custom launcher
 * uses), consumed by the Handheld Quick Menu's Notifications tab. Lives
 * here rather than in `:app` because `:shell-gamepad` cannot depend on
 * `:app`; the service and the shell share this in-process singleton.
 *
 * Access is a special grant (notification access, given on a system
 * screen droidtop opens). Until granted the listener never binds and
 * [items] stays empty — surfaces check [isGranted] and offer
 * [grantIntent] instead of showing a silently empty list.
 */
object NotificationsStore {

    /**
     * One notification, flattened to what the surfaces render — kept
     * free of StatusBarNotification so consumers never touch listener
     * types. [contentIntent] is the notification's own tap action;
     * [clearable] mirrors the system's rule (ongoing/foreground-service
     * notifications refuse dismissal, and offering X on one would be a
     * button that does nothing).
     */
    data class Item(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postTime: Long,
        val contentIntent: PendingIntent?,
        val clearable: Boolean,
    )

    private val mutableItems = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = mutableItems

    /** Set by the listener service while connected; null means not bound. */
    @Volatile
    var controller: Controller? = null

    interface Controller {
        fun dismiss(key: String)
        fun clearAll()
    }

    /** Called only by the listener service. */
    fun publish(list: List<Item>) {
        mutableItems.value = list.sortedByDescending { it.postTime }
    }

    fun isGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return flat.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == context.packageName
        }
    }

    fun grantIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
