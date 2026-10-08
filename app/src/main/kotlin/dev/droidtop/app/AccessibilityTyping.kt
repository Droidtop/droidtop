package dev.droidtop.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.droidtop.runtime.keyboard.AccessibilityKeyboard
import dev.droidtop.runtime.keyboard.AddonKeyboard
import dev.droidtop.runtime.keyboard.AddonKeyboardRules
import dev.droidtop.runtime.keyboard.FieldKey
import dev.droidtop.runtime.keyboard.FieldKeys
import dev.droidtop.runtime.keyboard.FieldText
import dev.droidtop.runtime.keyboard.OverlayOwner
import dev.droidtop.runtime.keyboard.TextEdit
import dev.droidtop.runtime.keyboard.TextSplice
import org.pocketworkstation.pckeyboard.KeyMeta
import org.pocketworkstation.pckeyboard.KeyboardSink
import org.pocketworkstation.pckeyboard.SecondScreenKeyboard
import java.util.concurrent.Executors

/**
 * droidtop's accessibility service for typing into other apps (docs/SPEC.md 4c, "Typing on the add-on display",
 * Droidtop/tracker#314). The user turns it on in Android's accessibility settings (Displays, "Keyboard through
 * accessibility"). It listens only for a view gaining focus or being tapped and for a window changing
 * (res/xml/typing_accessibility_service.xml), keeps only the one focused text field of another app, and reads that
 * field's text only to make the edit asked for. All of the work is [AccessibilityTyping].
 */
class TypingAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityTyping.connect(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { AccessibilityTyping.onEvent(this, it) }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityTyping.disconnect(this)
        return super.onUnbind(intent)
    }
}

/**
 * The accessibility route (docs/SPEC.md 4c): when a text field of another app gains focus on a display Android draws
 * no keyboard on, and droidtop's input method is not there to draw its own ([AddonKeyboardRules.overlayOwner]),
 * droidtop's keyboard is drawn at the bottom of that display in an accessibility overlay (no "Display over other
 * apps" grant) and types into the field through its accessibility actions ([FieldSink]). The companion's Keys panel
 * types through the same field ([RoutedKeyboardSink]). Events arrive on the main thread; actions on the field, which
 * are calls into the other app, run in order on one worker thread.
 */
internal object AccessibilityTyping {
    private const val HIDE_DELAY_MS = 400L
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "droidtop-accessibility-typing").apply { isDaemon = true } }
    private val overlay = PlacedKeyboard()
    private val hide = Runnable { overlay.hide() }
    private val overlaySink by lazy { RoutedKeyboardSink(displayId = { fieldDisplay }, elevated = { false }) }

    @Volatile
    private var service: AccessibilityService? = null

    /** The focused text field of another app, its display and its package: only ever this one. */
    @Volatile
    private var field: AccessibilityNodeInfo? = null

    @Volatile
    private var fieldDisplay: Int? = null

    private var fieldPackage: String? = null

    /** The field whose keyboard the user hid; a tap on it brings the keyboard back. Main thread. */
    private var dismissed: AccessibilityNodeInfo? = null

    /**
     * A password field's text as typed here since it gained focus: Android does not give a password's text to
     * accessibility, so the splice runs on what droidtop typed. Worker thread only.
     */
    private var passwordText: FieldText? = null

    fun connect(s: AccessibilityService) {
        service = s
        AccessibilityKeyboard.hasEditor = { service != null && field != null }
        AccessibilityKeyboard.connected = true
    }

    fun disconnect(s: AccessibilityService) {
        if (service !== s) return
        service = null
        AccessibilityKeyboard.connected = false
        main.post {
            forget()
            overlay.hide()
        }
    }

    fun onEvent(s: AccessibilityService, event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // droidtop's own windows type in their own window (InWindowKeyboard, OwnFieldKeyboard).
        if (pkg == s.packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                if (node.isEditable) {
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) dismissed = null
                    focus(s, node, pkg)
                } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && field != null && displayOf(node, pkg) == fieldDisplay) {
                    lose()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val current = fieldPackage ?: return
                if (pkg != current && displayOf(event.source, pkg) == fieldDisplay) lose()
            }
        }
    }

    private fun focus(s: AccessibilityService, node: AccessibilityNodeInfo, pkg: String) {
        val display = displayOf(node, pkg)
        if (node != field) worker.execute { passwordText = null }
        field = node
        fieldDisplay = display
        fieldPackage = pkg
        val owner = AddonKeyboardRules.overlayOwner(
            display,
            AddonKeyboard.localDisplays.value,
            droidtopImeSelected = SecondScreenKeyboard.imeRunning,
            canDrawOverlays = Settings.canDrawOverlays(s),
            accessibilityOn = true,
        )
        main.removeCallbacks(hide)
        if (owner != OverlayOwner.ACCESSIBILITY || display == null) {
            overlay.hide()
            return
        }
        if (node == dismissed) return
        show(s, display, pkg)
    }

    private fun lose() {
        forget()
        main.removeCallbacks(hide)
        main.postDelayed(hide, HIDE_DELAY_MS)
    }

    private fun forget() {
        field = null
        fieldDisplay = null
        fieldPackage = null
        dismissed = null
    }

    /**
     * An accessibility overlay on another display needs a context for that display: Android gives the service an
     * overlay token per display from Android 11 (`AccessibilityService.createDisplayContext`). Before that only the
     * built-in display can carry one.
     */
    private fun show(s: AccessibilityService, displayId: Int, pkg: String) {
        val focused = field
        overlay.show(
            displayId,
            windowContext = { overlayContext(s, displayId) },
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            sink = overlaySink,
            what = "keyboard over $pkg through accessibility",
            onHide = { dismissed = focused },
        )
    }

    /** A context that can add an accessibility overlay on [displayId], while the service is connected. */
    fun overlayContext(displayId: Int): Context? = service?.let { overlayContext(it, displayId) }

    private fun overlayContext(s: AccessibilityService, displayId: Int): Context? {
        if (Build.VERSION.SDK_INT < 30 && displayId != Display.DEFAULT_DISPLAY) return null
        if (displayId == Display.DEFAULT_DISPLAY) return s
        val display = s.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        return s.createDisplayContext(display)
    }

    /** The display [node] is on: its window says so from Android 11; before that, where droidtop placed the app. */
    private fun displayOf(node: AccessibilityNodeInfo?, pkg: String): Int? {
        if (Build.VERSION.SDK_INT >= 30) node?.window?.displayId?.let { return it }
        return appDisplay(pkg)
    }

    /** droidtop's keyboard typing into the focused field through its accessibility actions. */
    class FieldSink : KeyboardSink {
        private var metaState = 0

        override fun key(androidKeyCode: Int, down: Boolean) {
            metaState = KeyMeta.updated(metaState, androidKeyCode, down)
            if (!down || KeyMeta.updated(0, androidKeyCode, true) != 0) return
            val node = field ?: return
            val unicode = runCatching { VIRTUAL.get(androidKeyCode, metaState and KeyEvent.META_SHIFT_ON) }.getOrDefault(0)
            val char = unicode.takeIf { it != 0 && it and KeyCharacterMap.COMBINING_ACCENT == 0 }?.toChar()
            val key = FieldKeys.map(androidKeyCode, metaState, char, node.isMultiLine) ?: return
            worker.execute { runCatching { perform(node, key) } }
        }

        override fun text(chars: CharSequence) {
            val node = field ?: return
            val text = chars.toString()
            worker.execute { runCatching { perform(node, FieldKey.Edit(TextEdit.Insert(text))) } }
        }
    }

    private fun perform(node: AccessibilityNodeInfo, key: FieldKey) {
        if (!node.refresh()) return
        when (key) {
            FieldKey.Copy -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            FieldKey.Cut -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            FieldKey.Paste -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            FieldKey.ImeEnter -> if (Build.VERSION.SDK_INT >= 30) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            is FieldKey.Edit -> edit(node, key.edit)
        }
    }

    /**
     * One edit: read the field's text and selection, splice ([TextSplice]), write both back with ACTION_SET_TEXT and
     * ACTION_SET_SELECTION. A field without ACTION_SET_TEXT gets the typed text through the clipboard and
     * ACTION_PASTE at its selection, and a deletion as ACTION_CUT of the removed range; a password field without it
     * is not typed into.
     */
    private fun edit(node: AccessibilityNodeInfo, edit: TextEdit) {
        val password = node.isPassword
        val before = if (password) {
            passwordText ?: FieldText("", 0, 0)
        } else {
            FieldText.of(node.text, node.isShowingHintText, node.textSelectionStart, node.textSelectionEnd)
        }
        val after = TextSplice.apply(before, edit) ?: return
        if (after.text == before.text) {
            select(node, after.selStart, after.selEnd)
        } else if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, after.text) }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return
            select(node, after.selStart, after.selEnd)
        } else if (password) {
            // The clipboard route would put a password on the clipboard.
            return
        } else if (edit is TextEdit.Insert) {
            select(node, before.min, before.max)
            val clipboard = service?.getSystemService(ClipboardManager::class.java) ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("droidtop keyboard", edit.text))
            if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return
        } else {
            val removed = before.text.length - after.text.length
            select(node, after.min, after.min + removed)
            if (!node.performAction(AccessibilityNodeInfo.ACTION_CUT)) return
        }
        if (password) passwordText = after
    }

    private fun select(node: AccessibilityNodeInfo, start: Int, end: Int) {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private val VIRTUAL: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }
}
