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
import dev.droidtop.runtime.systemstatus.NotificationsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * KDE Connect's notices and what arrives from paired devices (docs/SPEC.md 7q "KDE Connect"):
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
    private const val RING_ID = 0x4B05
    /** Another device's notifications: one id each, from this base, by their key. */
    private const val MIRROR_BASE = 0x4C00
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts the device when it was left on, and follows its events for the life of the process. Called once at process start. */
    fun start(context: Context) {
        val app = context.applicationContext
        KdeConnect.resume(app)
        scope.launch {
            KdeConnect.events.collect { e -> runCatching { handle(app, e) } }
        }
        followClipboard(app)
        followBattery(app)
        followNotifications(app)
        holdMulticast(app)
    }

    /**
     * This device's notifications to paired devices (docs/SPEC.md 7q, slice 6): the ones droidtop's notification
     * listener already reads ([NotificationsStore], so there is one listener), when it may and the person left
     * "Send this device's notifications" on. New ones are posted, changed ones posted again silently, gone ones taken
     * away. A device that starts again has none, so the list is sent whole each time it starts.
     */
    private fun followNotifications(context: Context) {
        scope.launch {
            kotlinx.coroutines.flow.combine(
                KdeConnect.running,
                KdeConnect.changes.onStart { emit(0L) }.map { KdeConnect.sendsNotifications(context) && KdeConnect.pluginOn(context, "notifications") }
                    .distinctUntilChanged(),
                NotificationsStore.items,
            ) { running, sends, items -> if (running && sends) items.filter { it.forwardable } else null }
                .collect { items -> runCatching { mirror(items) } }
        }
    }

    /** What was last sent, by key: its title and text. Reset while KdeConnect is off or not sending. */
    private val sent = HashMap<String, Pair<String, String>>()

    private suspend fun mirror(items: List<NotificationsStore.Item>?) {
        if (items == null) {
            sent.keys.toList().forEach { KdeConnect.cancelNotification(it) }
            sent.clear()
            return
        }
        val now = items.associateBy { it.key }
        (sent.keys - now.keys).forEach { key ->
            KdeConnect.cancelNotification(key)
            sent.remove(key)
        }
        now.values.forEach { n ->
            val shown = n.title to n.text
            val before = sent[n.key]
            if (before == shown) return@forEach
            KdeConnect.postNotification(n.key, n.appLabel, n.title, n.text, n.clearable, silent = before != null, time = n.postTime)
            sent[n.key] = shown
        }
    }

    /**
     * The library also finds devices by mDNS (docs/SPEC.md 7q), which Wi-Fi drops on Android unless an app holds a
     * multicast lock: held while KDE Connect is on, released when it goes off (the permission is already droidtop's,
     * for pairing a computer).
     */
    private fun holdMulticast(context: Context) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
        val lock = runCatching { wifi.createMulticastLock("droidtop-kdeconnect").apply { setReferenceCounted(false) } }.getOrNull() ?: return
        scope.launch {
            KdeConnect.running.collect { on -> runCatching { if (on) lock.acquire() else if (lock.isHeld) lock.release() } }
        }
    }

    /** This device's battery to paired devices (docs/SPEC.md 7q, battery), from Android's battery broadcast. */
    private fun followBattery(context: Context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val present = intent.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, false)
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val charge = if (present && level >= 0 && scale > 0) level * 100 / scale else null
                val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
                scope.launch { KdeConnect.setBattery(charge, plugged) }
            }
        }
        val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // A sticky system broadcast: the current state arrives at once, then each change.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
        // The device may come on after that: send the state again whenever it does.
        scope.launch {
            KdeConnect.running.collect { on ->
                if (!on) return@collect
                val sticky = runCatching { context.registerReceiver(null, filter) }.getOrNull() ?: return@collect
                receiver.onReceive(context, sticky)
            }
        }
    }

    @Volatile
    private var ringing: android.media.Ringtone? = null

    @Volatile
    private var ringStarted = 0L

    /** Find my device: the alarm sound, loud, for 30 seconds or until the notice is opened. */
    private fun ring(context: Context, from: String) {
        val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE) ?: return
        val tone = android.media.RingtoneManager.getRingtone(context, uri) ?: return
        tone.audioAttributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
        ringing?.stop()
        ringing = tone
        ringStarted = android.os.SystemClock.elapsedRealtime()
        tone.play()
        post(context, RING_ID, "$from is looking for this device", "Open to stop the sound", screen(context, RING_ID))
        scope.launch {
            kotlinx.coroutines.delay(30_000)
            if (ringing === tone) stopRinging(context, force = true)
        }
    }

    /** Stops find my device's sound (opening the KDE Connect screen does). */
    fun stopRinging(context: Context, force: Boolean = false) {
        // The screen is drawn again on every change, the ring's own included: only a later opening stops it.
        if (!force && android.os.SystemClock.elapsedRealtime() - ringStarted < 3_000) return
        ringing?.stop()
        ringing = null
        context.getSystemService(NotificationManager::class.java)?.cancel(RING_ID)
    }

    /**
     * This device's clipboard to paired devices (docs/SPEC.md 7q, clipboard): Android tells droidtop of a change only
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
            is KdcEvent.Input -> typeHere(context, e)
            is KdcEvent.Ring -> ring(context, nameOf(e.id))
            is KdcEvent.Battery -> if (e.low) post(context, PING_ID, "${nameOf(e.id)}'s battery is low", "${e.charge}%", screen(context, PING_ID))
            // Another device's notifications, shown as its own (docs/SPEC.md 7q, notifications): one notice each,
            // updated in place and taken away when it goes there.
            is KdcEvent.Notification -> post(
                context,
                MIRROR_BASE + (e.id + e.key).hashCode().mod(0x3FF),
                listOf(e.app, e.title).filter { it.isNotBlank() }.joinToString(": ").ifBlank { nameOf(e.id) },
                e.text,
                screen(context, MIRROR_BASE),
                channel = MIRROR_CHANNEL,
                channelName = "Notifications from paired devices",
                silent = e.silent,
            )
            is KdcEvent.NotificationGone -> manager.cancel(MIRROR_BASE + (e.id + e.key).hashCode().mod(0x3FF))
            // The person dismissed one of this device's notifications on the other device.
            is KdcEvent.NotificationDismissed -> NotificationsStore.controller?.dismiss(e.key)
            is KdcEvent.Other -> Unit
        }
    }

    /**
     * Typing from a paired device (docs/SPEC.md 7q, remote input) through "Type from another device"'s one router: into
     * droidtop's own focused field, else through droidtop's input method into any app's field. Text, Backspace and
     * Enter are typed; a key it cannot type and a pointer move are not (the pointer needs an accessibility service,
     * not built). When there is no field, the router's notice is shown once a minute at most.
     */
    private fun typeHere(context: Context, e: KdcEvent.Input) {
        val key = e.key
        val (back, text, enter) = when {
            key != null -> Triple(0, key, false)
            e.specialKey == "backspace" -> Triple(1, "", false)
            e.specialKey == "enter" || e.specialKey == "linefeed" -> Triple(0, "", true)
            else -> {
                val code = e.specialKey?.let { dev.droidtop.library.credentials.handoff.RemoteKeys.keyCode(it) } ?: return
                runCatching { RemoteInputs.router().press(code) }.getOrNull()?.let { refused(context, it) }
                return
            }
        }
        val delivery = runCatching { RemoteInputs.router().deliver(back, text, enter) }.getOrNull() ?: return
        refused(context, delivery)
    }

    /** Says once a minute at most why typing from another device went nowhere. */
    private fun refused(context: Context, delivery: dev.droidtop.library.credentials.handoff.Delivery) {
        if (delivery is dev.droidtop.library.credentials.handoff.Delivery.Refused) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastTypingNotice > 60_000) {
                lastTypingNotice = now
                post(context, PING_ID, "Typing from another device", delivery.notice, screen(context, PING_ID))
            }
        }
    }

    @Volatile
    private var lastTypingNotice = 0L

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

    private const val MIRROR_CHANNEL = "kdeconnect_mirror"

    private fun post(
        context: Context,
        id: Int,
        title: String,
        text: String,
        tap: PendingIntent,
        channel: String = CHANNEL_ID,
        channelName: String = "KDE Connect",
        silent: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_HIGH))
        manager.notify(
            id,
            Notification.Builder(context, channel)
                .setOnlyAlertOnce(silent)
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
