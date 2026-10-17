package dev.droidtop.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.droidtop.runtime.keyboard.KeyboardPlacement
import dev.droidtop.shell.gamepad.KeyboardTargets
import org.pocketworkstation.pckeyboard.WindowKeySink

/**
 * Every web page droidtop shows (a plugin's sign-in, a store's, a key page) follows the one keyboard placement
 * (docs/SPEC.md 4c, "Typing on the add-on display"; the owner, 2026-10-17: "we need to stop surfacing a keyboard when
 * the companion exists"). While droidtop's keyboard for that screen is the companion's (or the internal screen's),
 * the page's window does not use Android's input method at all (FLAG_ALT_FOCUSABLE_IM), so Gboard cannot come up
 * however often the page asks for it (console, 2026-10-17: the F95 sign-in's WebView called showSoftInput four
 * times), and a field the page focuses opens the companion's slab, which types into the page through the window's
 * own key path ([WindowKeySink]) as an attached keyboard would. Elsewhere Android's keyboard serves the page as
 * before.
 *
 * The page says when one of its fields takes or loses focus through a script droidtop adds to every frame
 * ([WebViewCompat.addDocumentStartJavaScript]) and a message listener ([WebViewCompat.addWebMessageListener]); a
 * WebView without those features still keeps Android's keyboard away, and only the slab is not opened for it.
 */
object WebViewKeys {
    private const val LISTENER = "droidtopKeys"

    /** Posts "1" when an editable element takes focus and "0" when it loses it, in every frame. */
    private val SCRIPT = """
        (function () {
          if (window.__droidtopKeys) return;
          window.__droidtopKeys = true;
          function editable(t) {
            if (!t) return false;
            if (t.isContentEditable) return true;
            var n = (t.tagName || '').toUpperCase();
            if (n === 'TEXTAREA') return true;
            if (n !== 'INPUT') return false;
            var ty = (t.type || 'text').toLowerCase();
            return ['text', 'email', 'password', 'search', 'tel', 'url', 'number'].indexOf(ty) >= 0;
          }
          function say(on) { try { droidtopKeys.postMessage(on ? '1' : '0'); } catch (e) {} }
          document.addEventListener('focusin', function (e) { if (editable(e.target)) say(true); }, true);
          document.addEventListener('focusout', function (e) { if (editable(e.target)) say(false); }, true);
        })();
    """.trimIndent()

    /** Attaches the placement to [webView], right after it is made. Main thread. */
    fun attach(webView: WebView) {
        var request: KeyboardTargets.Request? = null
        fun displayId(): Int = webView.display?.displayId ?: Display.DEFAULT_DISPLAY
        fun servedElsewhere(): Boolean =
            when (KeyboardTargets.placement(displayId(), KeyboardTargets.assistiveOn(webView.context))) {
                KeyboardPlacement.COMPANION, KeyboardPlacement.INTERNAL_SCREEN -> true
                else -> false
            }
        fun keepAndroidKeyboardAway(away: Boolean) {
            val window = webView.context.findActivity()?.window ?: return
            if (away) {
                window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                KeyboardTargets.hideAndroidKeyboard(webView)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
        }
        fun closeRequest() {
            request?.let(KeyboardTargets::close)
            request = null
        }
        webView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            // The window and its display are known once the page is attached.
            override fun onViewAttachedToWindow(v: View) = keepAndroidKeyboardAway(servedElsewhere())

            override fun onViewDetachedFromWindow(v: View) = closeRequest()
        })
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return
        }
        WebViewCompat.addWebMessageListener(webView, LISTENER, setOf("*")) { _, message, _, _, _ ->
            // Called on the main thread.
            if (message.data != "1") {
                closeRequest()
                return@addWebMessageListener
            }
            val elsewhere = servedElsewhere()
            keepAndroidKeyboardAway(elsewhere)
            if (!elsewhere) return@addWebMessageListener
            closeRequest()
            val opened = KeyboardTargets.open(
                displayId(),
                WindowKeySink { webView },
                onHide = { request = null },
                assistive = KeyboardTargets.assistiveOn(webView.context),
            )
            request = (opened as? KeyboardTargets.Opened.Elsewhere)?.request
        }
        WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf("*"))
    }

    private fun Context.findActivity(): Activity? {
        var context: Context? = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }
}
