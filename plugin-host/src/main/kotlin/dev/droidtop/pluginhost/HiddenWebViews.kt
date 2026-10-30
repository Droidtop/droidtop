package dev.droidtop.pluginhost

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Where droidtop's hidden web views ([WebFetch], [HiddenWebSession]) are put while they work (Droidtop/tracker#783). A
 * web view that is in no window is a hidden page to its browser: it draws no frames and its timers are slowed, and a
 * site's browser check never finishes in it, so on the console F95's check that passed at once in the visible view
 * "did not pass by itself" in the hidden one. Each is laid behind the content of droidtop's screen in front, at that
 * screen's size, where the person never sees it and its page runs as in a visible view.
 */
object HiddenWebViews {
    /** The content view of droidtop's screen in front, set by `:app`; null when none is (a check from the background). */
    @Volatile
    var window: (() -> ViewGroup?)? = null

    /**
     * A web view made with the context of droidtop's screen in front and put behind its content, as the visible view is
     * made with its own screen's (Droidtop/tracker#783); null when no droidtop screen is in front. Call on the main thread.
     */
    fun open(): WebView? {
        val content = runCatching { window?.invoke() }.getOrNull() ?: return null
        val view = WebView(content.context)
        if (attach(view, content)) return view
        view.destroy()
        return null
    }

    /** Puts [view] behind [content]; whether it could. Call on the main thread. */
    private fun attach(view: WebView, content: ViewGroup): Boolean {
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        return runCatching {
            content.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }.isSuccess
    }

    /**
     * The visible view's page log, for a hidden one: the page's console messages go to droidtop's log under [tag], as the
     * visible view's do, and the view has a chrome client as that one has.
     */
    fun chromeClient(tag: String, host: String): android.webkit.WebChromeClient = object : android.webkit.WebChromeClient() {
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            android.util.Log.i(tag, "$host out of sight: console ${message.messageLevel()}: ${message.message()}")
            return true
        }
    }

    /** Takes [view] out of the window it was put in, if any. Call on the main thread, before destroying it. */
    fun detach(view: WebView) {
        (view.parent as? ViewGroup)?.let { parent -> runCatching { parent.removeView(view) } }
    }
}
