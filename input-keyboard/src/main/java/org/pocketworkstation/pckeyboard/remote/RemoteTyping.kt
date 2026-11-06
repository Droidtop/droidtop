package org.pocketworkstation.pckeyboard.remote

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.pocketworkstation.pckeyboard.SecondScreenKeyboard

/**
 * Typing from another device as the keyboard and input module owns it (docs/SPEC.md, "Type from another device",
 * Droidtop/tracker#645, #903): the one [RemoteInputRouter] of this process, wired to droidtop's own fields and to
 * droidtop's input method. Every remote source asks [router] for it: the phone page's session (`RemoteInput`, whose
 * transport, pairing and confirm stay with `HandoffServer` in `:library-core`) and KDE Connect's remote keyboard.
 * Nothing here decides whether a request may type; that is the session's, before it gets here.
 */
object RemoteTyping {
    /**
     * droidtop's own focused field, as a target; registered by the module that knows droidtop's windows
     * (`InWindowKeyboard.focusedOwnField`, `:shell-gamepad`, which depends on this module). Main thread.
     */
    @Volatile
    private var ownField: () -> RemoteTarget? = { null }

    /** Sets where droidtop's own focused field is found; the last call wins. */
    @JvmStatic
    fun ownFields(provider: () -> RemoteTarget?) {
        ownField = provider
    }

    /** The router: droidtop's own field first, else droidtop's input method's editor, else a notice. */
    fun router() = RemoteInputRouter(
        ownField = { ownField() },
        ime = {
            SecondScreenKeyboard.androidTarget?.let {
                ImeTarget(it, RemoteInputRouter.isPasswordInputType(SecondScreenKeyboard.androidInputType))
            }
        },
        imeSelected = { SecondScreenKeyboard.imeRunning },
        fieldThread = MainThread,
    )

    /** Delivers on the main thread, where the views and droidtop's input method live; the caller's thread waits for it. */
    object MainThread : FieldThread {
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
}

/**
 * droidtop's input method's editor as a target (docs/SPEC.md, "Type from another device", route b): text by
 * `commitText`, Backspace by `deleteSurroundingText` (a selection is replaced by nothing first), Enter and other keys
 * as key events.
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

    override fun enter() = key(KeyEvent.KEYCODE_ENTER)

    override fun key(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }
}
