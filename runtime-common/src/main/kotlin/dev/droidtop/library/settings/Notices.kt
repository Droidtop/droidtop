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

    /**
     * One flash. [mark] draws a job's own mark in place of the tone's glyph (a check that draws in, a cross that shakes;
     * Droidtop/tracker#643). [group] gathers flashes of one kind into one card: a flash of the same group while one is
     * still waiting updates that card, its [count] going up and its text becoming [groupLine]'s, instead of queueing a
     * second ("3 downloads finished", not three cards). [opens] is what a press on the card shows in place: the job's
     * page, or Downloads for a group.
     */
    class Flash internal constructor(
        val text: String,
        val tone: NoticeTone,
        val source: String?,
        val id: Long,
        val mark: JobLook? = null,
        val group: String? = null,
        val count: Int = 1,
        val opens: (() -> CatalogScreen?)? = null,
        internal val groupLine: ((Int) -> String)? = null,
        internal val groupOpens: (() -> CatalogScreen?)? = null,
    )

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
    fun flash(
        text: String,
        tone: NoticeTone = NoticeTone.Info,
        source: String? = null,
        private: Boolean = false,
        context: Context? = null,
        mark: JobLook? = null,
        group: String? = null,
        groupLine: ((Int) -> String)? = null,
        opens: (() -> CatalogScreen?)? = null,
        groupOpens: (() -> CatalogScreen?)? = null,
    ) {
        require(tone != NoticeTone.Danger) { "a flash needs no action; danger is a dialog" }
        val id = synchronized(this) { nextId++ }
        val incoming = Flash(text, tone, source, id, mark, group, 1, opens, groupLine, groupOpens)
        if (hosts.value.isEmpty() && context != null) {
            platformToast(context, incoming)
        } else {
            _flashes.update { merged(it, incoming) }
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

    /**
     * [waiting] with [incoming] added: a flash of a [Flash.group] already waiting becomes that card counted once more,
     * in its place and under its id, so a host showing it keeps showing it and sees it count up; else it queues. Pure.
     */
    fun merged(waiting: List<Flash>, incoming: Flash): List<Flash> {
        val group = incoming.group ?: return waiting + incoming
        val index = waiting.indexOfFirst { it.group == group }
        if (index < 0) return waiting + incoming
        val old = waiting[index]
        val count = old.count + 1
        val counted = Flash(
            text = old.groupLine?.invoke(count) ?: incoming.text,
            tone = incoming.tone,
            source = old.source,
            id = old.id,
            mark = incoming.mark,
            group = group,
            count = count,
            opens = old.groupOpens ?: incoming.opens,
            groupLine = old.groupLine,
            groupOpens = old.groupOpens,
        )
        return waiting.toMutableList().also { it[index] = counted }
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
