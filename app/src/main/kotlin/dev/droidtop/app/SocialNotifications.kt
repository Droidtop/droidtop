package dev.droidtop.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.SocialBadge
import dev.droidtop.library.social.SocialHub
import dev.droidtop.shell.standard.BackButtonMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Message notifications and the unread count for every social provider (docs/SPEC.md "Social",
 * Droidtop/tracker#327). A message that arrives while its conversation is not open is one notification
 * per conversation (the provider and the friend), titled with the friend's name, the service as its
 * sub-text, replaced by that conversation's next message; tapping it opens the Social place. Posted only
 * when notifications are already allowed for droidtop; whether a provider's messages notify at all is
 * the provider's own rule (Steam's switch on its page in Stores, a plugin's `notify.post` grant).
 */
object SocialNotifications {
    private const val CHANNEL_ID = "social_messages"

    /** The channel Steam's messages used before every provider shared one. */
    private const val OLD_STEAM_CHANNEL_ID = "steam_messages"

    /** Hooks the notifications and the Quick Menu's count into [SocialHub] once at process start. */
    fun install(context: Context) {
        val app = context.applicationContext
        SocialHub.notifier = { incoming -> show(app, incoming) }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { app.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(OLD_STEAM_CHANNEL_ID) }
            // The Quick Menu's Social tile reads these two numbers.
            SocialBadge.available = SocialHub.providers().isNotEmpty()
            SocialHub.changes().collect {
                SocialBadge.available = SocialHub.providers().isNotEmpty()
                SocialBadge.unread = SocialHub.unread()
            }
        }
    }

    /** What opens the Social place: Gaming, on that place. */
    fun openIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .putExtra(BackButtonMenu.EXTRA_MODE, Mode.GAMING.id)
        .putExtra(BackButtonMenu.EXTRA_GAMING_START_SECTION, "SOCIAL")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun show(context: Context, incoming: SocialHub.Incoming) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_DEFAULT))
            val id = incoming.conversationKey.hashCode()
            val open = PendingIntent.getActivity(
                context,
                id,
                openIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.notify(
                // One per conversation, replaced by its next message.
                id,
                Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle(incoming.name)
                    .setContentText(incoming.text)
                    .setSubText(incoming.providerLabel)
                    .setStyle(Notification.BigTextStyle().bigText(incoming.text))
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }
}
