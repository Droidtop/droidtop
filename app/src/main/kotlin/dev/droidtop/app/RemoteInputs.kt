package dev.droidtop.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import dev.droidtop.library.credentials.handoff.ClipRead
import dev.droidtop.library.credentials.handoff.ClipRules
import dev.droidtop.library.credentials.handoff.ConsoleClipboard
import dev.droidtop.library.credentials.handoff.FieldThread
import dev.droidtop.library.credentials.handoff.HandoffServer
import dev.droidtop.library.credentials.handoff.RemoteInput
import dev.droidtop.library.credentials.handoff.RemoteInputEnd
import dev.droidtop.library.credentials.handoff.RemoteInputRouter
import dev.droidtop.library.credentials.handoff.RemoteInputState
import dev.droidtop.library.credentials.handoff.RemoteTarget
import dev.droidtop.shell.gamepad.InWindowKeyboard
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.pocketworkstation.pckeyboard.SecondScreenKeyboard

/**
 * The one "Type from another device" session of this process (docs/SPEC.md, "Type from another device",
 * Droidtop/tracker#645) and the Android parts [RemoteInput] is given: where text lands ([router]), and the state
 * the console shows ([state]). Every way in (the companion's Keys screen, the Quick Menu row, the key setup screens)
 * opens [RemoteInputActivity], which starts the session through [RemoteInputService] when none runs.
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
            router = router(),
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

    private fun router() = RemoteInputRouter(
        ownField = { InWindowKeyboard.focusedOwnField() },
        ime = {
            SecondScreenKeyboard.androidTarget?.let {
                ImeTarget(it, RemoteInputRouter.isPasswordInputType(SecondScreenKeyboard.androidInputType))
            }
        },
        imeSelected = { SecondScreenKeyboard.imeRunning },
        fieldThread = MainThread,
    )
}

/**
 * The console's clipboard for the shared clipboard (docs/SPEC.md, "Type from another device", Droidtop/tracker#645),
 * both calls on the main thread. Reading follows what Android allows: only the app in front or the active input
 * method gets the clip, so from the background it is [ClipRead.Unavailable]. A clip marked sensitive
 * (`ClipDescription.EXTRA_IS_SENSITIVE`) is classified before any of its content is read, and only plain text
 * is read (`ClipData.Item.text`, never `coerceToText`, which would query a content provider).
 */
private class AndroidClipboard(private val context: Context) : ConsoleClipboard {
    private val manager get() = context.getSystemService(ClipboardManager::class.java)

    override fun write(text: String): Boolean = MainThread.call {
        val clipboard = manager ?: return@call false
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("droidtop shared clipboard", text))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    override fun read(): ClipRead = MainThread.call {
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

/** Delivers on the main thread, where the views and droidtop's input method live; the server's thread waits for it. */
private object MainThread : FieldThread {
    private val main = Handler(Looper.getMainLooper())

    override fun <T> call(block: () -> T): T {
        if (Looper.myLooper() === Looper.getMainLooper()) return block()
        val task = FutureTask(Callable { block() })
        main.post(task)
        // The server answers a failure with a 500 and keeps listening.
        return try {
            task.get(DELIVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            task.cancel(false)
            throw IOException("The main thread did not take the edit", e)
        }
    }

    private const val DELIVERY_TIMEOUT_MS = 2_000L
}

/**
 * droidtop's input method's editor as a target (docs/SPEC.md, "Type from another device", route b): text by
 * `commitText`, Backspace by `deleteSurroundingText` (a selection is replaced by nothing first), Enter as a key event.
 */
private class ImeTarget(private val connection: InputConnection, override val secret: Boolean) : RemoteTarget {
    override fun commit(text: String) {
        connection.commitText(text, 1)
    }

    override fun backspace(count: Int) {
        var left = count
        if (!connection.getSelectedText(0).isNullOrEmpty()) {
            connection.commitText("", 1)
            left--
        }
        if (left > 0) connection.deleteSurroundingText(left, 0)
    }

    override fun enter() {
        val now = android.os.SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
    }
}
