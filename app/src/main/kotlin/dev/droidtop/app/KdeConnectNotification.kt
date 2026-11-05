package dev.droidtop.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.droidtop.app.settings.KdeConnectCatalog
import dev.droidtop.library.credentials.handoff.ClipRead
import dev.droidtop.library.credentials.handoff.ClipRules
import dev.droidtop.library.credentials.handoff.FileNames
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.net.kdeconnect.KdcEvent
import dev.droidtop.net.kdeconnect.KdeConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * KDE Connect's notices and what arrives from paired devices (docs/SPEC.md 7p "KDE Connect"):
 * - a device asking to pair, with the code to compare; a ping;
 * - a shared link (tap opens it), a shared text (shown whole);
 * - a shared file: the library stores it whole in droidtop's cache, and it is moved here into the folder the person
 *   chose for received files, the same one as "Type from another device" ([ReceiveFolder], [AndroidConsoleFiles]:
 *   a chosen folder, else Download/droidtop), under a name that takes no other file's place;
 * - a file droidtop sent arriving, or not.
 * Like [LaunchWatchNotification] it posts only when the person has allowed droidtop's notifications.
 */
object KdeConnectNotification {
    private const val CHANNEL_ID = "kdeconnect"
    private const val PAIR_ID = 0x4B01
    private const val PING_ID = 0x4B02
    private const val SHARE_ID = 0x4B03
    private const val FILE_ID = 0x4B04
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts the device when it was left on, and follows its events for the life of the process. Called once at process start. */
    fun start(context: Context) {
        val app = context.applicationContext
        KdeConnect.resume(app)
        scope.launch {
            KdeConnect.events.collect { e -> runCatching { handle(app, e) } }
        }
        followClipboard(app)
    }

    /**
     * This device's clipboard to paired devices (docs/SPEC.md 7p, clipboard): Android tells droidtop of a change only
     * while it is in front or its input method is the active one, and only then lets it read the clip, so that is when
     * it is sent. The rules are the shared clipboard's ([ClipRules]): a clip marked sensitive never leaves, only plain
     * text, at most 64 KB. A clip that just arrived from a paired device is not sent back (the library holds it).
     */
    private fun followClipboard(context: Context) {
        val manager = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
        val clipboard = AndroidClipboard(context)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            manager.addPrimaryClipChangedListener {
                if (!KdeConnect.running.value) return@addPrimaryClipChangedListener
                scope.launch { sendClipboard(clipboard) }
            }
        }
    }

    /** Sends the clipboard as it is now; what happened, in words for the screen. */
    suspend fun sendClipboard(clipboard: dev.droidtop.library.credentials.handoff.ConsoleClipboard): String =
        when (val read = clipboard.read()) {
            is ClipRead.Text -> if (!ClipRules.fits(read.text)) {
                "The clipboard holds more than 64 KB, so it is not sent"
            } else {
                val n = KdeConnect.clipboardSet(read.text)
                if (n == 0) "Paired devices already have it, or none is connected" else "Sent to $n device${if (n == 1) "" else "s"}"
            }
            ClipRead.Sensitive -> "The clipboard holds something marked sensitive, so it is not sent"
            ClipRead.Empty -> "The clipboard is empty"
            ClipRead.NotText -> "The clipboard holds something other than text"
            ClipRead.Unavailable -> "Android did not let droidtop read the clipboard"
        }

    /** Sends this device's clipboard now (the screen's Send clipboard row). */
    suspend fun sendClipboard(context: Context): String = sendClipboard(AndroidClipboard(context.applicationContext))

    private suspend fun nameOf(id: String): String = KdeConnect.devices().firstOrNull { it.id == id }?.name ?: "A paired device"

    private suspend fun handle(context: Context, e: KdcEvent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        when (e) {
            is KdcEvent.PairRequest -> post(context, PAIR_ID, "${e.name} asks to pair", "Pair only if it shows ${e.key}. Open to accept or refuse", screen(context, PAIR_ID))
            is KdcEvent.Paired, is KdcEvent.PairFailed -> manager.cancel(PAIR_ID)
            is KdcEvent.Ping -> post(context, PING_ID, "Ping from ${nameOf(e.id)}", e.message ?: "Ping!", screen(context, PING_ID))
            // Only web links open on a tap; any other scheme (intent:, file:, content:) is shown as text.
            is KdcEvent.ShareUrl -> if (!(e.url.startsWith("https://", true) || e.url.startsWith("http://", true))) {
                post(context, SHARE_ID, "Link from ${nameOf(e.id)}", e.url, screen(context, SHARE_ID))
            } else {
                val open = PendingIntent.getActivity(
                    context,
                    SHARE_ID,
                    Intent(Intent.ACTION_VIEW, Uri.parse(e.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                post(context, SHARE_ID, "Link from ${nameOf(e.id)}", e.url, open)
            }
            is KdcEvent.ShareText -> post(context, SHARE_ID, "Text from ${nameOf(e.id)}", e.text, screen(context, SHARE_ID))
            is KdcEvent.FileReceiving -> {
                val size = e.size?.let { " (${FileNames.size(it)})" } ?: ""
                post(context, FILE_ID, "Receiving ${e.name}$size", "From ${nameOf(e.id)}", screen(context, FILE_ID))
            }
            is KdcEvent.FileReceived -> {
                val text = when (val stored = store(context, File(e.path), e.name)) {
                    null -> "Could not store ${e.name}: ${ReceiveFolder.label(context)} cannot be written to"
                    else -> "${nameOf(e.id)} sent $stored (${FileNames.size(e.size)}) to ${ReceiveFolder.label(context)}"
                }
                post(context, FILE_ID, "File received", text, screen(context, FILE_ID))
            }
            is KdcEvent.FileFailed -> post(context, FILE_ID, "Could not receive ${e.name}", e.why, screen(context, FILE_ID))
            is KdcEvent.FileSent -> post(context, FILE_ID, "Sent ${e.name}", "${nameOf(e.id)} has it (${FileNames.size(e.size)})", screen(context, FILE_ID))
            is KdcEvent.FileSendFailed -> post(context, FILE_ID, "Could not send ${e.name}", e.why, screen(context, FILE_ID))
            // Written as the shared clipboard writes (64 KB at most); nothing is shown, as in the official apps.
            is KdcEvent.Clipboard -> if (ClipRules.fits(e.text)) AndroidClipboard(context).write(e.text)
            is KdcEvent.Other -> Unit
        }
    }

    /**
     * Moves a file the library stored whole in the cache into the person's folder for received files; the name it
     * was stored under, or null when that folder cannot be written to. The cache copy is deleted either way.
     */
    private fun store(context: Context, file: File, name: String): String? {
        val folder = AndroidConsoleFiles(context)
        try {
            val stored = FileNames.unique(FileNames.sanitise(name)) { folder.exists(it) } ?: return null
            val incoming = folder.create(stored) ?: return null
            try {
                file.inputStream().use { it.copyTo(incoming.out, 64 * 1024) }
                incoming.out.flush()
                incoming.finish()
            } catch (e: IOException) {
                incoming.discard()
                return null
            }
            return incoming.name
        } finally {
            file.delete()
        }
    }

    private fun screen(context: Context, code: Int): PendingIntent = PendingIntent.getActivity(
        context,
        code,
        CatalogScreenLink.intent(context, KdeConnectCatalog.SCREEN_ID),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(context: Context, id: Int, title: String, text: String, tap: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "KDE Connect", NotificationManager.IMPORTANCE_HIGH))
        manager.notify(
            id,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build(),
        )
    }
}
