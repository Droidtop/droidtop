package dev.droidtop.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.droidtop.library.credentials.handoff.ClipRead
import dev.droidtop.library.credentials.handoff.ClipRules
import dev.droidtop.library.credentials.handoff.ConsoleClipboard
import dev.droidtop.library.credentials.handoff.HandoffServer
import dev.droidtop.library.credentials.handoff.RemoteInput
import dev.droidtop.library.credentials.handoff.RemoteInputEnd
import dev.droidtop.library.credentials.handoff.RemoteInputState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.pocketworkstation.pckeyboard.ClipboardHistoryStore
import org.pocketworkstation.pckeyboard.remote.RemoteTyping

/**
 * The one "Type from another device" session of this process (docs/SPEC.md, "Type from another device",
 * Droidtop/tracker#645) and the Android parts [RemoteInput] is given: the state the console shows ([state]), the
 * clipboard and the folder. Where text lands is the keyboard and input module's ([RemoteTyping.router],
 * Droidtop/tracker#903).
 * Every way in (the companion's Keys screen, the Quick Menu row, the key setup screens) opens [RemoteInputActivity],
 * which starts the session through [RemoteInputService] when none runs.
 */
object RemoteInputs {
    private val current = MutableStateFlow<RemoteInputState>(RemoteInputState.Idle)

    /** What the console shows; Idle or Closed when no session runs. */
    val state: StateFlow<RemoteInputState> = current

    @Volatile
    private var session: RemoteInput? = null

    val running: Boolean get() = current.value.let { it !is RemoteInputState.Idle && it !is RemoteInputState.Closed }

    /** Starts the foreground service that runs the session, unless one runs already. Main thread, with droidtop in front. */
    fun start(context: Context) {
        if (running) return
        current.value = RemoteInputState.Idle
        context.startForegroundService(Intent(context, RemoteInputService::class.java).setAction(RemoteInputService.ACTION_START))
    }

    /** Binds the LAN address and starts listening. Off the main thread, from the service. */
    fun begin(context: Context) {
        val host = HandoffServer.lanAddress()
        if (host == null) {
            current.value = RemoteInputState.Closed(RemoteInputEnd.NO_NETWORK)
            return
        }
        val input = RemoteInput(
            clock = System::currentTimeMillis,
            router = RemoteTyping.router(),
            listen = { endpoint -> HandoffServer(endpoint, host) },
            host = host,
            state = current,
            clipboard = AndroidClipboard(context.applicationContext),
            files = AndroidConsoleFiles(context.applicationContext),
        )
        session = input
        input.start()
    }

    /**
     * Offers the file [uri] names (the document picker's result) to the phone's page (docs/SPEC.md, "Type from another
     * device", file transfer). Reads the file's name and size, so off the main thread. False when no session runs or the
     * URI is not one droidtop may hand out.
     */
    fun offer(context: Context, uri: Uri): Boolean {
        val file = AndroidConsoleFiles.offered(context.applicationContext, uri) ?: return false
        return session?.offer(file) ?: false
    }

    /** Takes the offered file away. */
    fun withdrawOffer() {
        session?.withdrawOffer()
    }

    fun confirm() {
        session?.confirm()
    }

    fun refuse() {
        session?.refuse()
    }

    /** Ends the session and its listener. Safe from any thread, and when none runs. */
    fun close() {
        session?.close()
        session = null
    }
}

/**
 * The console's clipboard for the shared clipboard (docs/SPEC.md, "Type from another device", Droidtop/tracker#645),
 * both calls on the main thread. What a paired device writes (the phone page, KDE Connect) is also kept in the
 * keyboard's clipboard history ([ClipboardHistoryStore.fromPairedDevice]). Reading follows what Android allows: only
 * the app in front or the active input method gets the clip, so from the background it is [ClipRead.Unavailable]. A
 * clip marked sensitive
 * (`ClipDescription.EXTRA_IS_SENSITIVE`) is classified before any of its content is read, and only plain text
 * is read (`ClipData.Item.text`, never `coerceToText`, which would query a content provider).
 */
internal class AndroidClipboard(private val context: Context) : ConsoleClipboard {
    private val manager get() = context.getSystemService(ClipboardManager::class.java)

    override fun write(text: String): Boolean = RemoteTyping.MainThread.call {
        val clipboard = manager ?: return@call false
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("droidtop shared clipboard", text))
        } catch (e: SecurityException) {
            return@call false
        }
        // The paired device's clip also lands on the keyboard's clipboard panel, under its rules (Droidtop/tracker#903).
        ClipboardHistoryStore.fromPairedDevice(context, text)
        true
    }

    override fun read(): ClipRead = RemoteTyping.MainThread.call {
        val clipboard = manager ?: return@call ClipRead.Unavailable
        try {
            if (!clipboard.hasPrimaryClip()) return@call ClipRead.Empty
            val clip = clipboard.primaryClip ?: return@call ClipRead.Unavailable
            val description = clip.description
            ClipRules.classify(
                present = clip.itemCount > 0,
                sensitive = description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true,
                isText = description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true ||
                    description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) == true,
                text = { clip.getItemAt(0).text },
            )
        } catch (e: SecurityException) {
            ClipRead.Unavailable
        }
    }

    private companion object {
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
