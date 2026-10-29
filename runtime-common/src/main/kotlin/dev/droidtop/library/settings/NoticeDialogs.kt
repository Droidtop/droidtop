package dev.droidtop.library.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    /**
     * Waits up to [timeoutMs] for a surface to attach; true once one is. For a question asked during a switch, when
     * the screen just left has no host and the one arriving attaches only once it composes (Droidtop/tracker#389:
     * the keep-running offer was answered Cancel before Gaming could draw it).
     */
    suspend fun awaitHost(timeoutMs: Long): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { hosts.first { it.isNotEmpty() } } != null

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
