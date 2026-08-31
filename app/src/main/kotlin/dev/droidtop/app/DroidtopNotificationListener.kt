package dev.droidtop.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.droidtop.runtime.systemstatus.NotificationsStore

/**
 * Feeds [NotificationsStore] — the same NotificationListenerService
 * mechanism every custom launcher's notification surface uses. Binds
 * only after the user grants notification access (the Quick Menu's
 * Notifications tab offers the grant screen until then).
 *
 * The full active list is republished on every change rather than
 * diffed: the system's `activeNotifications` is the authority, a
 * republish is O(a few dozen), and incremental bookkeeping against an
 * authoritative source is a classic drift generator.
 */
class DroidtopNotificationListener : NotificationListenerService() {

    private val controller = object : NotificationsStore.Controller {
        override fun dismiss(key: String) {
            runCatching { cancelNotification(key) }
        }

        override fun clearAll() {
            runCatching { cancelAllNotifications() }
        }
    }

    override fun onListenerConnected() {
        NotificationsStore.controller = controller
        publishAll()
    }

    override fun onListenerDisconnected() {
        NotificationsStore.controller = null
        NotificationsStore.publish(emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = publishAll()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = publishAll()

    private fun publishAll() {
        val pm = packageManager
        val list = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                    ?: extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
                // A notification with neither title nor text is a group
                // header or a rendering shell -- nothing a list row can
                // show, so it is skipped rather than rendered blank.
                if (title.isNullOrBlank() && text.isNullOrBlank()) return@mapNotNull null
                val appLabel = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                }.getOrDefault(sbn.packageName)
                NotificationsStore.Item(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appLabel = appLabel,
                    title = title.orEmpty().ifBlank { appLabel },
                    text = text.orEmpty(),
                    postTime = sbn.postTime,
                    contentIntent = sbn.notification.contentIntent,
                    clearable = sbn.isClearable,
                )
            }
        NotificationsStore.publish(list)
    }
}
