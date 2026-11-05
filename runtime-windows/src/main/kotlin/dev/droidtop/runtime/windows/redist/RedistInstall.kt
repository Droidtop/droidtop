package dev.droidtop.runtime.windows.redist

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.winlator.container.Container
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import dev.droidtop.library.theme.RoleTokens
import dev.droidtop.runtime.windows.WineXSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Installs a game's pending redistributables ([RedistPlan]) in its prefix before the game starts (docs/SPEC.md 9,
 * "Redistributable installers"; Droidtop/tracker#916). For each: its licence on droidtop's own sheet over the game
 * screen (Accept / Decline / "Remember for other games", pad and touch), unless remembered; then the installer, run
 * out of sight in the prefix with its silent switches, and the outcome in the prefix's record. Decline installs nothing
 * and is asked again next time. The installer's own window never shows.
 *
 * [run] blocks: call it off the main thread, before the game's own session starts.
 */
internal class RedistInstall(
    private val activity: Activity,
    private val parent: FrameLayout,
    private val chrome: () -> RoleTokens.Set?,
) {
    private enum class Answer { ACCEPT, ACCEPT_REMEMBER, DECLINE }

    @Volatile private var sheet: View? = null
    private var buttons: List<View> = emptyList()
    private var onAnswer: ((Answer) -> Unit)? = null
    private var remember: CheckBox? = null

    val showing: Boolean get() = sheet != null

    fun run(prefix: Container, pending: List<RedistPlan.Pending>) {
        for (p in pending) {
            val licence = p.row.licence
            val accepted = licence == null || RedistLicences.accepted(activity, licence) || when (ask(p, licence)) {
                Answer.ACCEPT_REMEMBER -> { RedistLicences.remember(activity, licence); true }
                Answer.ACCEPT -> true
                Answer.DECLINE -> false
            }
            if (!accepted) {
                Timber.tag(TAG).i("%s: licence declined, not installed", p.key)
                continue
            }
            status("Installing ${p.title}…")
            val code = install(prefix, p)
            val ok = code in SUCCESS
            RedistRecord.write(prefix.rootDir, p.key, if (ok) "installed ($code)" else "failed ($code)")
            Timber.tag(TAG).i("%s from %s: exit %d (%s)", p.key, p.file.path, code, if (ok) "installed" else "failed")
        }
        status(null)
    }

    /** The installer in the prefix, out of sight and silent; its exit code, or -1 if it didn't end in [TIMEOUT_MIN]. */
    private fun install(prefix: Container, p: RedistPlan.Pending): Int {
        val ended = CountDownLatch(1)
        var exit = -1
        val xServer = XServer(ScreenInfo(prefix.screenSize), false)
        val session = WineXSession(activity, prefix, p.file.path, p.file.parentFile!!, xServer, p.row.switches, silent = true)
        return try {
            session.start { code, _ -> exit = code; ended.countDown() }
            if (!ended.await(TIMEOUT_MIN, TimeUnit.MINUTES)) Timber.tag(TAG).w("%s: no end after %d min, stopped", p.key, TIMEOUT_MIN)
            exit
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "%s: could not run", p.key)
            -1
        } finally {
            runCatching { session.stop() }
        }
    }

    /** Shows the licence sheet for [p] and waits for the person's answer. */
    private fun ask(p: RedistPlan.Pending, licence: RedistTable.Licence): Answer {
        val latch = CountDownLatch(1)
        var answer = Answer.DECLINE
        activity.runOnUiThread {
            show(p, licence) { a -> answer = a; latch.countDown() }
        }
        latch.await()
        return answer
    }

    private fun show(p: RedistPlan.Pending, licence: RedistTable.Licence, answered: (Answer) -> Unit) {
        val tokens = chrome()
        val ground = tokens?.color("surface") ?: Color.rgb(24, 24, 24)
        val ink = tokens?.color("onSurface") ?: Color.WHITE
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val title = TextView(activity).apply {
            text = "This game installs ${p.title}"
            setTextColor(ink)
            textSize = 20f
        }
        val body = TextView(activity).apply {
            text = RedistLicences.text(licence) + "\n\nIt installs out of sight, then the game starts."
            setTextColor(ink)
        }
        val box = CheckBox(activity).apply {
            text = "Remember for other games"
            setTextColor(ink)
        }
        val accept = Button(activity).apply { text = "Accept"; setOnClickListener { answer(if (box.isChecked) Answer.ACCEPT_REMEMBER else Answer.ACCEPT) } }
        val decline = Button(activity).apply { text = "Decline"; setOnClickListener { answer(Answer.DECLINE) } }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(accept)
            addView(decline)
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ground)
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(body)
            addView(box)
            addView(row)
        }
        parent.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ).apply { leftMargin = pad * 2; rightMargin = pad * 2 })
        sheet = card
        remember = box
        buttons = listOf(box, accept, decline)
        onAnswer = answered
        accept.requestFocus()
    }

    private fun answer(a: Answer) {
        sheet?.let(parent::removeView)
        sheet = null
        buttons = emptyList()
        remember = null
        onAnswer?.invoke(a)
        onAnswer = null
    }

    private var line: TextView? = null

    /** A one-line status over the black screen while an installer runs; null clears it. */
    private fun status(text: String?) {
        activity.runOnUiThread {
            if (text == null) {
                line?.let(parent::removeView)
                line = null
                return@runOnUiThread
            }
            val view = line ?: TextView(activity).apply {
                setTextColor(chrome()?.color("onSurface") ?: Color.WHITE)
                gravity = Gravity.CENTER
                parent.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }.also { line = it }
            view.text = text
        }
    }

    /**
     * The pad while the sheet is up: Up/Down/Left/Right move between the checkbox, Accept and Decline, A or Enter presses
     * one, B declines. Everything else is the sheet's while it shows.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (sheet == null) return false
        if (event.action != KeyEvent.ACTION_UP) return true
        val focused = buttons.indexOfFirst { it.isFocused }.coerceAtLeast(1)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> buttons.getOrNull((focused + buttons.size - 1) % buttons.size)?.requestFocus()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> buttons.getOrNull((focused + 1) % buttons.size)?.requestFocus()
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> buttons.getOrNull(focused)?.performClick()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> answer(Answer.DECLINE)
        }
        return true
    }

    private companion object {
        const val TAG = "droidtop.redist"
        const val TIMEOUT_MIN = 10L

        /** Success, success needing a restart (3010, 1641), and "a newer or the same version is already installed" (1638). */
        val SUCCESS = setOf(0, 3010, 1641, 1638)
    }
}
