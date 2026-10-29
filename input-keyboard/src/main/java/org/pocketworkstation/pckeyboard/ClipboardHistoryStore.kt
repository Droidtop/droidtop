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
 * the app's private storage, encrypted with a key that stays in the Android Keystore ([ClipboardVault],
 * Droidtop/tracker#772). Encoding, sealing and every file read and write run on one background thread; the main
 * thread only touches memory.
 *
 * Android lets the focused app or the current input method read the clipboard (SPEC 6d), so recording works while
 * droidtop's keyboard is the selected one; a read Android refuses is skipped. Never recorded: a clip the source
 * flagged sensitive (`android.content.extra.IS_SENSITIVE`), a copy made while the keyboard serves a password field or
 * an editor that asked for no personalised learning ([IncognitoRules.sensitiveField], since apps before Android 13
 * never set the flag), and anything while incognito. Turning incognito on forgets everything but the pinned entries,
 * in memory and in the file.
 */
object ClipboardHistoryStore {
    private const val TAG = "HK/ClipboardHistory"
    private const val FILE = "clipboard_history.bin"

    /** The file before it was encrypted: read once into the history, then deleted ([load]). */
    private const val PLAINTEXT_FILE = "clipboard_history.txt"
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val history = ClipboardHistory()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "hk-clipboard-history").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var started = false
    private lateinit var appContext: Context

    /**
     * Whether the last field the keyboard served is sensitive ([noteField]); a copy made meanwhile is not kept. It
     * holds until the next field starts, so a copy made just after leaving a password field is not kept either.
     */
    @Volatile
    private var fieldSensitive = false

    /**
     * Notes the field the keyboard now serves ([IncognitoRules.sensitiveField]); the input method calls it as each
     * field starts. Main thread.
     */
    @JvmStatic
    fun noteField(inputType: Int, imeOptions: Int) {
        fieldSensitive = IncognitoRules.sensitiveField(inputType, imeOptions)
    }

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
        val sensitive = fieldSensitive || clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true
        // Plain text only: coercing a URI or HTML item would query a content provider.
        val text = clip.getItemAt(0).text?.toString() ?: return
        val added = synchronized(history) { history.add(text, System.currentTimeMillis(), sensitive = sensitive) }
        if (added) changed()
    }

    /** Tells the listeners, then saves a copy of the entries; the encoding and sealing happen on [io]. */
    private fun changed() {
        listeners.forEach { it() }
        val entries = synchronized(history) { history.entries }
        io.execute { save(ClipboardHistory.encode(entries)) }
    }

    /**
     * Reads the sealed file into the history, merged with anything recorded meanwhile ([ClipboardHistory.restore]).
     * The plaintext file an older build wrote is read the same way once, then saved sealed and deleted, so no copied
     * text stays on disk in the clear; it is gone after the first start that finds it.
     */
    private fun load() {
        val sealed = File(appContext.filesDir, FILE)
        val plaintext = File(appContext.filesDir, PLAINTEXT_FILE)
        val encoded = buildList {
            if (sealed.exists()) {
                val bytes = try {
                    sealed.readBytes()
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "history not read", e)
                    null
                }
                val opened = bytes?.let { ClipboardVault.open(it) }
                if (bytes != null && opened == null) Log.w(TAG, "history not readable with this device's key; starting afresh")
                opened?.let { add(String(it, Charsets.UTF_8)) }
            }
            if (plaintext.exists()) {
                try {
                    add(plaintext.readText())
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "old history not read", e)
                }
            }
        }
        synchronized(history) { encoded.forEach { history.restore(it) } }
        if (plaintext.exists()) {
            save(ClipboardHistory.encode(synchronized(history) { history.entries }))
            if (!plaintext.delete()) Log.w(TAG, "old plaintext history not deleted")
        }
        listeners.forEach { it() }
    }

    /** Seals [encoded] and replaces the file with it. On [io] only. */
    private fun save(encoded: String) {
        try {
            val file = File(appContext.filesDir, FILE)
            val tmp = File(appContext.filesDir, "$FILE.tmp")
            tmp.writeBytes(ClipboardVault.seal(encoded.toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(file)) Log.w(TAG, "history not saved: rename failed")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "history not saved", e)
        } catch (e: java.security.GeneralSecurityException) {
            Log.w(TAG, "history not saved: the key could not seal it", e)
        }
    }
}
