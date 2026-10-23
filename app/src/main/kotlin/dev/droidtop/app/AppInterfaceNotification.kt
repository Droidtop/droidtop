package dev.droidtop.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.droidtop.library.settings.Place
import dev.droidtop.pluginhost.AppInterfaceTrust
import dev.droidtop.pluginhost.AppInterfaces

/**
 * The one notification an app that carries a droidtop plugin gets, once per app version, when it announces itself or
 * droidtop finds it (docs/plugin-api.md 1.3, [dev.droidtop.pluginhost.AppInterfaceAnnouncements]; Droidtop/tracker#547).
 * Tapping it opens Plugins, where "In your apps" adds it. Posted only when notifications are already allowed for
 * droidtop, like [dev.droidtop.app.update.PluginRepoUpdateNotification]; without that the app is still under "In your
 * apps".
 */
object AppInterfaceNotification {
    private const val CHANNEL_ID = "app_interfaces"

    fun show(context: Context, app: AppInterfaces.Found) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Apps that work with droidtop", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context,
                0,
                PlaceLinks.intent(context, Place.PLUGINS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = when (app.verdict) {
                is AppInterfaceTrust.Verdict.Official -> "Official. Add it under Plugins, In your apps, to choose what it may do"
                else -> "Unofficial. Under Plugins, In your apps, you can check its key and add it"
            }
            manager.notify(
                // One per app, replaced by its next version's.
                ("app:" + app.packageName).hashCode(),
                Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle("${app.appLabel} can work with droidtop")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }
}
