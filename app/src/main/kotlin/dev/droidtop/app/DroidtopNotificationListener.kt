package dev.droidtop.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.droidtop.library.social.AppMessages
import dev.droidtop.runtime.systemstatus.NotificationApps
import dev.droidtop.runtime.systemstatus.NotificationRows
import dev.droidtop.runtime.systemstatus.NotificationsStore

/**
 * Feeds [NotificationsStore] and [AppMessages] (the conversations other apps expose, Social place) — the same NotificationListenerService
 * mechanism every custom launcher's notification surface uses. Binds
 * only after the user grants notification access (the Quick Menu's
 * Notifications tab offers the grant screen until then).
 *
 * Each change is built into the model on one background thread ([publishAll]); app labels, icons and whether
 * an app provides a VPN come from [NotificationApps]'s per-package cache. Status (ongoing, foreground, progress) is
 * marked, and the status droidtop's own indicators already show is dropped ([NotificationRows.dropped]).
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

        override fun reply(key: String, text: String): Result<Unit> {
            val sbn = runCatching { activeNotifications?.firstOrNull { it.key == key } }.getOrNull()
                ?: return Result.failure(dev.droidtop.library.UserFacingException("That notification is gone"))
            val action = dev.droidtop.library.social.NotificationReply.replyAction(sbn.notification)
                ?: return Result.failure(dev.droidtop.library.UserFacingException("This one can only be answered in its app"))
            return dev.droidtop.library.social.NotificationReply.send(this@DroidtopNotificationListener, action, text)
        }
    }

    override fun onListenerConnected() {
        NotificationsStore.controller = controller
        AppMessages.bind(this)
        publishAll()
    }

    override fun onListenerDisconnected() {
        NotificationsStore.controller = null
        NotificationsStore.publish(emptyList())
        AppMessages.unbind()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = publishAll()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = publishAll()

    override fun onDestroy() {
        builder.shutdownNow()
        super.onDestroy()
    }

    /**
     * The listener only asks for a rebuild; the model is built on [builder], one background thread, so the callback
     * never waits on the package manager or the notification list (Droidtop/tracker#786 S13a: "The listener only
     * snapshots, and the model is built in the background"). A burst of posts while one build waits runs one build.
     */
    private val builder = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "droidtop-notifications").apply { isDaemon = true }
    }
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun publishAll() {
        if (!pending.compareAndSet(false, true)) return
        runCatching {
            builder.execute {
                pending.set(false)
                runCatching { build() }
            }
        }.onFailure { pending.set(false) }
    }

    private fun build() {
        val active = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()
        // The conversations other apps expose are read from the same list.
        AppMessages.onPosted(active)
        val list = active.mapNotNull { sbn -> item(sbn) }
        NotificationsStore.publish(list)
    }

    /** One notification as the model's item, or null when there is nothing to show or droidtop already shows it. */
    private fun item(sbn: StatusBarNotification): NotificationsStore.Item? {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val (line, detail) = NotificationRows.lines(
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty(),
        )
        // A notification with neither title nor text is a group header or a rendering shell: nothing a row can show.
        if (title.isNullOrBlank() && line.isBlank()) return null
        val progress = NotificationRows.progress(
            now = extras.getInt(Notification.EXTRA_PROGRESS, 0),
            max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
            indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false),
        )
        val flags = notification.flags
        val status = NotificationRows.isStatus(
            ongoing = sbn.isOngoing,
            foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            noClear = flags and Notification.FLAG_NO_CLEAR != 0,
            hasProgress = progress != null,
        )
        val app = NotificationApps.of(this, sbn.packageName)
        if (NotificationRows.dropped(status, sbn.packageName, app.vpnProvider)) return null
        return NotificationsStore.Item(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = app.label,
            title = title.orEmpty().ifBlank { app.label },
            text = line,
            postTime = sbn.postTime,
            contentIntent = notification.contentIntent,
            clearable = sbn.isClearable,
            canReply = dev.droidtop.library.social.NotificationReply.replyAction(notification) != null,
            message = notification.category == Notification.CATEGORY_MESSAGE ||
                extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name,
            status = status,
            progress = progress,
            detail = detail,
            icon = app.icon,
        )
    }
}
