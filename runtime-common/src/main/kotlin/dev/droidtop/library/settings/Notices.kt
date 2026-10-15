package dev.droidtop.library.settings

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One line of droidtop's own recent notices (docs/SPEC.md 7k, "Notices"): what a flash said, and when. */
class RecentNotice(val text: String, val tone: NoticeTone, val source: String?, val atMillis: Long)

/**
 * The flash: a short corner notification that needs no action ("Saved", "Plugin removed"), and the Recent list
 * it leaves behind for the Quick Menu's Notifications tab (docs/SPEC.md 7k, "Notices"). [flash] decides nothing
 * about where it is drawn: the host on top draws it. A value the person marked private ([private]) is shown but
 * never kept in Recent.
 */
object Notices {
    /** How many entries Recent keeps, in memory only. */
    const val RECENT_MAX = 50

    class Flash internal constructor(val text: String, val tone: NoticeTone, val source: String?, val id: Long)

    private val _flashes = MutableStateFlow<List<Flash>>(emptyList())

    /** Flashes waiting to be drawn, oldest first; the host removes each with [shown]. */
    val flashes: StateFlow<List<Flash>> = _flashes.asStateFlow()

    private val _recent = MutableStateFlow<List<RecentNotice>>(emptyList())

    /** droidtop's own recent notices, newest first. */
    val recent: StateFlow<List<RecentNotice>> = _recent.asStateFlow()

    private val _unread = MutableStateFlow(0)

    /** Entries added since Recent was last looked at (the Notifications tab's dot). */
    val unread: StateFlow<Int> = _unread.asStateFlow()

    private var nextId = 0L

    private val hosts = MutableStateFlow<Set<Any>>(emptySet())

    /** A surface that draws flashes is on screen (started); while none is, a flash falls back to a platform toast. */
    fun attachFlashHost(token: Any) = hosts.update { it + token }

    fun detachFlashHost(token: Any) = hosts.update { it - token }

    /**
     * Shows [text] as a flash. With no droidtop surface on screen to draw it (a plugin's message during a game) and a
     * [context] to post with, it is a platform toast led by [source] instead, so it is still seen; it is kept in
     * Recent either way.
     */
    fun flash(text: String, tone: NoticeTone = NoticeTone.Info, source: String? = null, private: Boolean = false, context: Context? = null) {
        require(tone != NoticeTone.Danger) { "a flash needs no action; danger is a dialog" }
        val id = synchronized(this) { nextId++ }
        if (hosts.value.isEmpty() && context != null) {
            platformToast(context, Flash(text, tone, source, id))
        } else {
            _flashes.update { it + Flash(text, tone, source, id) }
        }
        if (!private) {
            _recent.update { (listOf(RecentNotice(text, tone, source, System.currentTimeMillis())) + it).take(RECENT_MAX) }
            _unread.update { (it + 1).coerceAtMost(RECENT_MAX) }
        }
    }

    /** [flash] as Android's own toast, led by its source: where no droidtop surface can draw the card above what is open. */
    fun platformToast(context: Context, flash: Flash) {
        val shown = if (flash.source != null) "${flash.source}: ${flash.text}" else flash.text
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, shown, Toast.LENGTH_SHORT).show()
        }
    }

    /** The host drew [flash] for its time; it is no longer waiting. */
    fun shown(flash: Flash) = _flashes.update { list -> list.filterNot { it.id == flash.id } }

    /** The person has seen Recent. */
    fun markRead() {
        _unread.value = 0
    }

    /** Recent as plain text, newest first, for Copy all. */
    fun recentAsText(zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).apply { timeZone = zone }
        return _recent.value.joinToString("\n") { entry ->
            buildString {
                append(format.format(java.util.Date(entry.atMillis))).append("  ").append(entry.tone.spokenName)
                entry.source?.let { append("  ").append(it) }
                append("  ").append(entry.text)
            }
        }
    }
}
