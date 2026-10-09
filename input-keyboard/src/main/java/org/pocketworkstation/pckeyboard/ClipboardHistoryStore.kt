package org.pocketworkstation.pckeyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The process's clipboard history (docs/SPEC.md 6a, "Editing helpers", Droidtop/tracker#340): the model
 * ([ClipboardHistory]) kept in memory, recorded from the primary clip as it changes (no polling), saved to a file in
 * the app's private storage. All file work runs on one background thread; the main thread only touches memory.
 *
 * Android lets the focused app or the current input method read the clipboard (SPEC 6d), so recording works while
 * droidtop's keyboard is the selected one; a read Android refuses is skipped. A clip the source flagged sensitive
 * (`android.content.extra.IS_SENSITIVE`) is never recorded, nothing is recorded while incognito, and turning
 * incognito on forgets everything but the pinned entries.
 */
object ClipboardHistoryStore {
    private const val TAG = "HK/ClipboardHistory"
    private const val FILE = "clipboard_history.txt"
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val history = ClipboardHistory()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "hk-clipboard-history").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var started = false
    private lateinit var appContext: Context

    // Held here because the preference store keeps listeners weakly.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == ToolsPrefs.INCOGNITO && prefs.getBoolean(ToolsPrefs.INCOGNITO, false)) {
            synchronized(history) { history.clear(keepPinned = true) }
            changed()
        }
    }

    /** Starts recording in this process; calling it again does nothing. Main thread. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        io.execute { load() }
        appContext.getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener { record() }
        ToolsPrefs.prefs(appContext).registerOnSharedPreferenceChangeListener(prefListener)
    }

    /** A copy of the entries, pinned first then newest first. */
    fun snapshot(): List<ClipboardHistory.Entry> = synchronized(history) { history.entries }

    fun setPinned(text: String, pinned: Boolean) {
        if (synchronized(history) { history.setPinned(text, pinned) }) changed()
    }

    fun delete(text: String) {
        if (synchronized(history) { history.delete(text) }) changed()
    }

    /** Forgets the unpinned entries. */
    fun clear() {
        synchronized(history) { history.clear(keepPinned = true) }
        changed()
    }

    /** [listener] runs, on any thread, whenever the entries change. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun record() {
        val context = appContext
        if (!ToolsPrefs.clipboardHistory(context) || ToolsPrefs.incognito(context)) return
        val clip = try {
            context.getSystemService(ClipboardManager::class.java)?.primaryClip
        } catch (e: SecurityException) {
            Log.i(TAG, "clipboard read refused")
            null
        } ?: return
        if (clip.itemCount == 0) return
        val sensitive = clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true
        // Plain text only: coercing a URI or HTML item would query a content provider.
        val text = clip.getItemAt(0).text?.toString() ?: return
        val added = synchronized(history) { history.add(text, System.currentTimeMillis(), sensitive = sensitive) }
        if (added) changed()
    }

    private fun changed() {
        listeners.forEach { it() }
        val encoded = synchronized(history) { history.encode() }
        io.execute { save(encoded) }
    }

    private fun load() {
        val file = File(appContext.filesDir, FILE)
        val encoded = try {
            if (file.exists()) file.readText() else return
        } catch (e: java.io.IOException) {
            Log.w(TAG, "history not read", e)
            return
        }
        synchronized(history) { history.restore(encoded) }
        listeners.forEach { it() }
    }

    private fun save(encoded: String) {
        try {
            val file = File(appContext.filesDir, FILE)
            val tmp = File(appContext.filesDir, "$FILE.tmp")
            tmp.writeText(encoded)
            if (!tmp.renameTo(file)) Log.w(TAG, "history not saved: rename failed")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "history not saved", e)
        }
    }
}
