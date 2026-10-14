package dev.droidtop.library.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A danger dialog's question (docs/SPEC.md 7k, "Notices": the danger dialog). [notice] is a Danger notice: its
 * title, its outcome as the body, its "We recommend Cancel." as the recommendation and its exact terms as the
 * details. [items] lists the plugins it is about when there are several (one dialog for all of them).
 */
class DangerConfirm(
    val notice: Notice,
    val proceedLabel: String,
    val items: List<String> = emptyList(),
    val cancelLabel: String = "Cancel",
)

/** What a confirm dialog came back with. Anything but a deliberate press of the proceed button is [Cancel]. */
enum class ConfirmAnswer { Proceed, Cancel }

/**
 * The dialogs that ask before something changes (docs/SPEC.md 7k, "Notices"): the danger dialog and the plain
 * confirm. Code anywhere (a catalog's suspend action, the plugin host) asks and suspends; whichever surface is
 * showing has attached itself as the host and draws the question in its own window, with Cancel on the left and
 * focused. With no host attached nothing can be asked, so the answer is Cancel: a risky change never happens
 * without the person seeing it.
 */
object NoticeDialogs {
    /** One open question. [danger] is null for a plain confirm. */
    class Request internal constructor(
        val notice: Notice,
        val proceedLabel: String,
        val cancelLabel: String,
        val items: List<String>,
        val danger: Boolean,
    ) {
        internal val answer = CompletableDeferred<ConfirmAnswer>()
    }

    private val _pending = MutableStateFlow<Request?>(null)

    /** The question a host should draw now, or null. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private val hosts = MutableStateFlow<List<Any>>(emptyList())

    /** Whether [token] is the host that draws: the one attached last (the surface on top). */
    fun drawsHere(token: Any): Boolean = hosts.value.lastOrNull() === token

    /** The hosts in attach order, for a host composable to observe [drawsHere] changing. */
    val hostTokens: StateFlow<List<Any>> = hosts.asStateFlow()

    fun attachHost(token: Any) = hosts.update { it - token + token }

    fun detachHost(token: Any) = hosts.update { it - token }

    /** Asks the danger question; suspends until it is answered. Cancel when no surface can ask. */
    suspend fun confirmDanger(confirm: DangerConfirm): ConfirmAnswer =
        ask(Request(confirm.notice, confirm.proceedLabel, confirm.cancelLabel, confirm.items, danger = true))

    /** The same card for an Info or Caution question (a plugin's `ui.prompt confirm` too), with no danger fill. */
    suspend fun confirm(notice: Notice, cancelLabel: String = "Cancel", proceedLabel: String): ConfirmAnswer =
        ask(Request(notice, proceedLabel, cancelLabel, emptyList(), danger = false))

    private suspend fun ask(request: Request): ConfirmAnswer {
        if (hosts.value.isEmpty()) return ConfirmAnswer.Cancel
        // One at a time: a second question waits for the first to be answered.
        while (true) {
            val current = _pending.value ?: if (_pending.compareAndSet(null, request)) break else continue
            current.answer.await()
        }
        return try {
            request.answer.await()
        } finally {
            request.answer.complete(ConfirmAnswer.Cancel)
            _pending.compareAndSet(request, null)
        }
    }

    /** The host's answer. A dismissal that is not a press of the proceed button is [ConfirmAnswer.Cancel]. */
    fun answer(request: Request, answer: ConfirmAnswer) {
        request.answer.complete(answer)
        _pending.compareAndSet(request, null)
    }
}

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

    fun flash(text: String, tone: NoticeTone = NoticeTone.Info, source: String? = null, private: Boolean = false) {
        require(tone != NoticeTone.Danger) { "a flash needs no action; danger is a dialog" }
        val id = synchronized(this) { nextId++ }
        _flashes.update { it + Flash(text, tone, source, id) }
        if (!private) {
            _recent.update { (listOf(RecentNotice(text, tone, source, System.currentTimeMillis())) + it).take(RECENT_MAX) }
            _unread.update { (it + 1).coerceAtMost(RECENT_MAX) }
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
