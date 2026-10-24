package dev.droidtop.library.credentials.handoff

import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/** Why a [RemoteInput] ended. */
enum class RemoteInputEnd(val text: String) {
    PERSON("Closed"),
    IDLE("Closed after ten minutes with nothing typed"),
    NO_NETWORK("No network. Connect to Wi-Fi and try again"),
}

/** What the console shows of a [RemoteInput]. */
sealed interface RemoteInputState {
    data object Idle : RemoteInputState

    /** The QR code and [url] are up. [refused]: the last pairing was refused or timed out, so this is a new address. */
    data class Waiting(val url: String, val refused: Boolean = false) : RemoteInputState

    /** A device opened the page and shows [code]; the console asks whether it does too. */
    data class Confirming(val code: String) : RemoteInputState

    /** One device is paired and typing. [lastSent] is already masked for a password; [notice] says why nothing went. */
    data class Paired(val lastSent: String? = null, val notice: String? = null) : RemoteInputState

    data class Closed(val reason: RemoteInputEnd) : RemoteInputState
}

/** The line the console says about the session, in the screen and in the notification. */
fun RemoteInputState.consoleLine(): String = when (this) {
    RemoteInputState.Idle -> "Starting"
    is RemoteInputState.Waiting ->
        if (refused) "That code was refused. Scan the new QR code" else "Scan the QR code with a device on this network"
    is RemoteInputState.Confirming -> "Does your device show $code?"
    is RemoteInputState.Paired -> "Phone connected: typing"
    is RemoteInputState.Closed -> reason.text
}

/**
 * Typing from another device (docs/SPEC.md, "Type from another device", Droidtop/tracker#645): one session on top of
 * [HandoffServer]. The device's LAN address on a random port serves a page at `/r/<token>`; the first device to open
 * it is shown a random six digit code, and the console asks whether the device shows the same ([confirm] or
 * [refuse]). Nothing is accepted before Confirm. Only that one device is served afterwards; any other gets a 409.
 * A refusal, or sixty seconds with no answer, closes the listener and starts a new one on a new port with a new
 * token and, for the next device, a new code: pairing starts over, and a code is never used twice. The session ends
 * when the person closes it or after ten minutes with no request, and the listener goes with it.
 *
 * The token and the code are compared in constant time. Typing is delivered by [router]; it is called outside this
 * class's lock, because it waits for the main thread. Time passes in [tick], which the server's own loop calls
 * every second ([HandoffEndpoint.isLive]); a test calls it with its own clock.
 */
class RemoteInput(
    private val clock: () -> Long,
    private val router: RemoteInputRouter,
    private val listen: (HandoffEndpoint) -> HandoffListener,
    private val host: String,
    val state: MutableStateFlow<RemoteInputState> = MutableStateFlow(RemoteInputState.Idle),
    private val random: SecureRandom = SecureRandom(),
    private val promptTimeoutMs: Long = PROMPT_TIMEOUT_MS,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) {
    private var generation: Generation? = null
    private var listener: HandoffListener? = null
    private var port = 0
    private var token = ""
    private var device: String? = null
    private var code: String? = null
    private var paired = false
    private var promptDeadline = 0L
    private var lastActivity = 0L
    private var started = false
    private var closed = false
    private var lastSent: String? = null
    private val usedCodes = HashSet<String>()

    /** One listener's endpoint: once the session has moved on to another, it answers nothing and ends its loop. */
    private inner class Generation : HandoffEndpoint {
        override fun isLive(): Boolean {
            tick()
            return synchronized(this@RemoteInput) { generation === this }
        }

        override fun handle(method: String, target: String, body: String, peer: String): HandoffReply =
            respond(this, method, target, body, peer)
    }

    /** The address the QR code shows; null while no listener is up. */
    @Synchronized
    fun url(): String? = if (closed || !started) null else HandoffServer.url(host, port, token)

    /** Starts listening and shows the QR code. Off the main thread. A second call does nothing. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        lastActivity = clock()
        listenAgain(refused = false)
    }

    /** The person says the code matches: from now on the paired device's typing is accepted. */
    @Synchronized
    fun confirm() {
        if (closed || code == null || paired) return
        paired = true
        lastActivity = clock()
        state.value = RemoteInputState.Paired()
    }

    /** The person says it does not: the listener is replaced and pairing starts over. */
    @Synchronized
    fun refuse() {
        if (closed || code == null || paired) return
        listenAgain(refused = true)
    }

    /** Ends the session and its listener. */
    @Synchronized
    fun close() = end(RemoteInputEnd.PERSON)

    /** Applies the prompt timeout and the idle limit. */
    @Synchronized
    fun tick() {
        if (!started || closed) return
        val now = clock()
        when {
            now - lastActivity >= idleTimeoutMs -> end(RemoteInputEnd.IDLE)
            code != null && !paired && now >= promptDeadline -> listenAgain(refused = true)
        }
    }

    private fun end(reason: RemoteInputEnd) {
        if (closed) return
        closed = true
        listener?.stop()
        listener = null
        generation = null
        token = ""
        device = null
        code = null
        state.value = RemoteInputState.Closed(reason)
    }

    /** Closes the listener, if one is up, and opens a new one: new token, new port, nobody paired, no code. */
    private fun listenAgain(refused: Boolean) {
        val before = port
        listener?.stop()
        token = newToken()
        device = null
        code = null
        paired = false
        lastSent = null
        val next = Generation()
        generation = next
        var tries = 0
        while (true) {
            val open = listen(next)
            val bound = runCatching { open.start() }.getOrNull()
            if (bound == null) {
                open.stop()
                return end(RemoteInputEnd.NO_NETWORK)
            }
            // Never back on the port just closed: a device that kept the old address would find the new session.
            if (bound == before && tries++ < REBIND_TRIES) {
                open.stop()
                continue
            }
            listener = open
            port = bound
            break
        }
        state.value = RemoteInputState.Waiting(HandoffServer.url(host, port, token), refused)
    }

    /** What the server thread does with one request, decided under the lock; delivery happens outside it. */
    private sealed interface Verdict {
        class Answer(val reply: HandoffReply) : Verdict

        class Edit(val back: Int, val text: String, val enter: Boolean) : Verdict

        data object Status : Verdict
    }

    private fun respond(from: Generation, method: String, target: String, body: String, peer: String): HandoffReply {
        val verdict = synchronized(this) { if (generation === from) judge(method, target, body, peer) else Verdict.Answer(NOT_FOUND) }
        return when (verdict) {
            is Verdict.Answer -> verdict.reply
            Verdict.Status -> status()
            is Verdict.Edit -> deliver(verdict)
        }
    }

    private fun judge(method: String, target: String, body: String, peer: String): Verdict {
        if (closed) return Verdict.Answer(GONE)
        val parts = target.substringBefore('?').split('/')
        // "", "r", token [, "state" | "in"]
        if (parts.size !in 3..4 || parts[0] != "" || parts[1] != "r" || !sameSecret(parts[2], token)) return Verdict.Answer(NOT_FOUND)
        if (method != "GET" && method != "POST") return Verdict.Answer(NOT_FOUND)
        val what = parts.getOrNull(3)
        val now = clock()
        if (method == "GET" && what == null) {
            if (device == null) {
                device = peer
                code = newCode()
                promptDeadline = now + promptTimeoutMs
                state.value = RemoteInputState.Confirming(spaced(code!!))
            } else if (device != peer) {
                return Verdict.Answer(TAKEN)
            }
            lastActivity = now
            return Verdict.Answer(HandoffReply(200, RemoteInputPage.html(code!!)))
        }
        if (device == null) return Verdict.Answer(HandoffReply(403, page("Open the address from the console first.")))
        if (device != peer) return Verdict.Answer(TAKEN)
        // The page's own status polling is not typing: only the page opening and an edit keep the session from going idle.
        return when {
            method == "GET" && what == "state" -> if (paired) Verdict.Status else Verdict.Answer(json("""{"state":"confirming"}"""))
            method == "POST" && what == "in" -> judgeEdit(body, now)
            else -> Verdict.Answer(NOT_FOUND)
        }
    }

    private fun judgeEdit(body: String, now: Long): Verdict {
        if (!paired) return Verdict.Answer(HandoffReply(403, page("Confirm the code on the console first.")))
        val form = parseForm(body)
        if (!sameSecret(form["code"].orEmpty(), code.orEmpty())) return Verdict.Answer(HandoffReply(403, page("Wrong code.")))
        val back = form["back"]?.toIntOrNull() ?: 0
        val text = form["text"].orEmpty()
        if (back !in 0..MAX_TEXT || text.length > MAX_TEXT || text.any { it.isISOControl() }) return Verdict.Answer(HandoffReply(400, ""))
        lastActivity = now
        return Verdict.Edit(back, text, form["enter"] == "1")
    }

    private fun status(): HandoffReply = json("""{"state":"paired","secret":${router.secret()}}""")

    private fun deliver(edit: Verdict.Edit): HandoffReply {
        val delivery = router.deliver(edit.back, edit.text, edit.enter)
        synchronized(this) {
            if (!closed && paired) {
                when (delivery) {
                    is Delivery.Sent -> {
                        lastSent = RemoteInputRouter.forConsole(describe(edit), delivery.secret)
                        state.value = RemoteInputState.Paired(lastSent)
                    }
                    is Delivery.Refused -> state.value = RemoteInputState.Paired(lastSent, delivery.notice)
                }
            }
        }
        return when (delivery) {
            is Delivery.Sent -> json("""{"ok":true,"secret":${delivery.secret}}""")
            is Delivery.Refused -> json("""{"ok":false,"notice":"${delivery.notice}"}""")
        }
    }

    private fun describe(edit: Verdict.Edit): String = when {
        edit.text.isNotEmpty() -> edit.text
        edit.enter -> "Enter"
        else -> "Backspace"
    }

    private fun newCode(): String {
        while (true) {
            val candidate = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
            if (usedCodes.add(candidate)) return candidate
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val PROMPT_TIMEOUT_MS = 60_000L
        const val IDLE_TIMEOUT_MS = 10 * 60_000L
        private const val REBIND_TRIES = 3
        private const val MAX_TEXT = 1024

        val NOT_FOUND = HandoffReply(404, "")
        private val GONE = HandoffReply(410, page("This session has ended."))
        private val TAKEN = HandoffReply(409, page("Another device is already connected to this session."))

        /** Equality that takes the same time wherever the two differ. */
        fun sameSecret(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        /** "482913" as the console and the phone say it: "482 913". */
        fun spaced(code: String): String = code.chunked(3).joinToString(" ")

        private fun json(body: String) = HandoffReply(200, body, "application/json")

        private fun page(message: String) = RemoteInputPage.message(message)

        private fun parseForm(body: String): Map<String, String> =
            body.split('&').filter { it.contains('=') }.associate {
                val (k, v) = it.split('=', limit = 2)
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
    }
}
