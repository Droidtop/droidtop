package dev.droidtop.app.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.droidtop.app.PlaceLinks
import dev.droidtop.app.R
import dev.droidtop.library.integrations.PluginRepoUpdates
import dev.droidtop.library.integrations.RepoCheckResult
import dev.droidtop.library.settings.Place

/**
 * The one notification the plugin-repository update pass posts (docs/SPEC.md 12a "Plugin
 * repositories"): what was updated, and what now needs the person (an approval, or access an
 * update newly asks for). Posted only when notifications are already allowed for droidtop, like
 * [dev.droidtop.app.JobsSummaryNotification]; without that permission the same words are on the
 * repository's screen under "Checked ...". Tapping it opens the Updates place in Gaming, the one screen that
 * lists what has a newer version.
 */
object PluginRepoUpdateNotification {
    private const val CHANNEL_ID = "plugin_repo_updates"

    fun show(context: Context, repo: String, result: RepoCheckResult) {
        runCatching {
            if (result !is RepoCheckResult.Processed) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Plugin updates", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context,
                0,
                // The Updates place, in the mode the person is using.
                PlaceLinks.intent(context, Place.UPDATES),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.notify(
                // One notification per repository, replaced by its next result.
                repo.lowercase().hashCode(),
                Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle("Plugins from $repo")
                    .setContentText(PluginRepoUpdates.describe(result))
                    .setStyle(Notification.BigTextStyle().bigText(PluginRepoUpdates.describe(result)))
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }
}
