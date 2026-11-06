package org.pocketworkstation.pckeyboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.PersistableBundle
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

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
 * never set the flag), and anything while incognito ([ClipIntake], the one rule for the listener, the keyboard's own
 * writes and a paired device). Turning incognito on forgets everything but the pinned entries, in memory and in the
 * file. Unpinned entries older than "Keep clips for" ([ClipKeep]) are removed on this thread when the file is loaded,
 * when the panel is about to open ([prepareForPanel]) and when the setting changes; pinned ones stay.
 */
object ClipboardHistoryStore {
    private const val TAG = "HK/ClipboardHistory"
    private const val FILE = "clipboard_history.bin"

    /** The file before it was encrypted: read once into the history, then deleted ([load]). */
    private const val PLAINTEXT_FILE = "clipboard_history.txt"

    /**
     * `ClipDescription.EXTRA_IS_SENSITIVE`, by its value: the constant is API 33, the key is read and written on every
     * version (password managers set it before Android 13 too).
     */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    /** The label of a clip the keyboard itself puts on the clipboard ([putOnClipboard]). */
    const val KEYBOARD_LABEL = "droidtop keyboard"

    private val history = ClipboardHistory()
    private val io = KeyboardThreads.single("hk-clipboard-history")
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
        // A shorter "Keep clips for" applies at once.
        if (key == ToolsPrefs.CLIPBOARD_KEEP) io.execute { expire() }
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
        if (synchronized(history) { history.setPinned(text, pinned, System.currentTimeMillis()) }) changed()
    }

    fun delete(text: String) {
        if (synchronized(history) { history.delete(text) }) changed()
    }

    /** Forgets the unpinned entries ("Clear unpinned"); pinned ones stay. */
    fun clearUnpinned() {
        synchronized(history) { history.clear(keepPinned = true) }
        changed()
    }

    /**
     * The panel is about to open: clips past "Keep clips for" go first, on this store's thread, and the listeners hear
     * of it. Expiry waits for the panel to open rather than running while it is shown, so a row never vanishes under
     * the finger (HeliBoard's `prepareClipboardHistory`). Main thread.
     */
    fun prepareForPanel(context: Context) {
        start(context)
        io.execute { expire() }
    }

    /** Whether [description] carries the sensitive mark ([EXTRA_IS_SENSITIVE]); readable on every version. */
    @JvmStatic
    fun markedSensitive(description: ClipDescription?): Boolean =
        description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true

    /**
     * The keyboard's own write to the clipboard: a clip or emoji for a container to paste ([ToolsDeck]), or text
     * droidtop types into a field through the clipboard (`AccessibilityTyping`). [typed] text is not a copy the person
     * made, so it is marked sensitive: the history never keeps it ([ClipIntake]), and Android 13's clipboard preview
     * does not show it. Any other write is recorded by the listener under the same rules as any clip. Any thread: the
     * typing route writes from its own worker.
     */
    @JvmStatic
    fun putOnClipboard(context: Context, text: CharSequence, typed: Boolean): Boolean {
        val clip = ClipData.newPlainText(KEYBOARD_LABEL, text)
        if (typed) clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        return try {
            val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
            manager.setPrimaryClip(clip)
            true
        } catch (e: SecurityException) {
            Log.i(TAG, "clipboard write refused")
            false
        }
    }

    /** [listener] runs, on any thread, whenever the entries change. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * A clip a paired device just put on the clipboard (the phone page's Send to console, a KDE Connect device's
     * clipboard), under the history's one rule ([ClipIntake]; neither source can mark a clip sensitive today, so
     * [markedSensitive] is false from both, kept so a source that can is never stored): kept
     * from the text that was written, so it reaches the panel even when Android would not let droidtop read the
     * clipboard back (droidtop not in front, its keyboard not the active one). Starts recording if nothing has yet.
     * Main thread; the settings are read on the history's own thread, since a first read loads them from disk.
     */
    fun fromPairedDevice(context: Context, text: String, markedSensitive: Boolean = false) {
        start(context)
        val app = appContext
        // Taken now: the field the keyboard serves when the clip arrives, not when the thread gets to it.
        val sensitiveNow = fieldSensitive
        io.execute {
            val outcome = synchronized(history) {
                ClipIntake.keep(
                    history, text, System.currentTimeMillis(),
                    historyOn = ToolsPrefs.clipboardHistory(app),
                    incognito = ToolsPrefs.incognito(app),
                    fieldSensitive = sensitiveNow,
                    markedSensitive = markedSensitive,
                )
            }
            if (outcome.changed) changed()
        }
    }

    private fun record() {
        val context = appContext
        // Not even read while nothing would be kept.
        if (!ToolsPrefs.clipboardHistory(context) || ToolsPrefs.incognito(context)) return
        val clip = try {
            context.getSystemService(ClipboardManager::class.java)?.primaryClip
        } catch (e: SecurityException) {
            Log.i(TAG, "clipboard read refused")
            null
        } ?: return
        if (clip.itemCount == 0) return
        // Plain text only: coercing a URI or HTML item would query a content provider.
        val text = clip.getItemAt(0).text?.toString() ?: return
        val outcome = synchronized(history) {
            ClipIntake.keep(
                history, text, System.currentTimeMillis(),
                historyOn = ToolsPrefs.clipboardHistory(context),
                incognito = ToolsPrefs.incognito(context),
                fieldSensitive = fieldSensitive,
                markedSensitive = markedSensitive(clip.description),
            )
        }
        if (outcome.changed) changed()
    }

    /** Unpinned clips past "Keep clips for" go ([ClipKeep]). On [io] only: the first read of the settings is a file read. */
    private fun expire() {
        val keep = ToolsPrefs.clipboardKeep(appContext)
        if (synchronized(history) { history.expire(System.currentTimeMillis(), keep.millis) }) changed()
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
        val expired = synchronized(history) {
            encoded.forEach { history.restore(it) }
            // Clips that outlived "Keep clips for" while the keyboard was not running go before anyone sees them.
            history.expire(System.currentTimeMillis(), ToolsPrefs.clipboardKeep(appContext).millis)
        }
        if (expired && !plaintext.exists()) save(ClipboardHistory.encode(synchronized(history) { history.entries }))
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
