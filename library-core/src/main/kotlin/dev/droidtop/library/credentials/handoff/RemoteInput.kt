package dev.droidtop.library.credentials.handoff

import java.io.IOException
import java.io.InputStream
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

    /**
     * One device is paired and typing. [lastSent] is already masked for a password; [notice] says why nothing went.
     * [clip] is a line of the text the phone just put on the console's clipboard, shown for a few seconds.
     * [receiving] is the file the phone is sending now ("name (size)"), [received] the line about the last one that
     * arrived ("Phone sent name (size)"), [offered] the file the console offers the phone ("name (size)").
     */
    data class Paired(
        val lastSent: String? = null,
        val notice: String? = null,
        val clip: String? = null,
        val receiving: String? = null,
        val received: String? = null,
        val offered: String? = null,
    ) : RemoteInputState

    data class Closed(val reason: RemoteInputEnd) : RemoteInputState
}

/** The line the console says about the session, in the screen and in the notification. */
fun RemoteInputState.consoleLine(): String = when (this) {
    RemoteInputState.Idle -> "Starting"
    is RemoteInputState.Waiting ->
        if (refused) "That code was refused. Scan the new QR code" else "Scan the QR code with a device on this network"
    is RemoteInputState.Confirming -> "Does your device show $code?"
    // What the phone is sending, or last sent, is what the person looks for, on the screen and in the notification.
    is RemoteInputState.Paired -> receiving?.let { "Receiving $it" } ?: received ?: "Phone connected: typing"
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
 * The same session carries a shared clipboard (`clipput` and `clipget`, each request carrying the code like an edit):
 * text of at most [ClipRules.MAX_BYTES] bytes goes onto the console's [clipboard] and is shown on the console briefly,
 * and the console's clip text goes to the phone unless the source marked it sensitive.
 *
 * The same session carries file transfer (`put` and `get`, slice 3): a file the phone sends is streamed to disk into the
 * folder [files] names, at most [FileNames.MAX_BYTES], under a sanitised name that never replaces a file; one file the
 * console offers ([offer]) is a download link on the phone's page for this session only. Both follow the pairing, code
 * and token rules of an edit, and neither is ever held in memory.
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
    private val clipboard: ConsoleClipboard = ConsoleClipboard.None,
    private val clipShowMs: Long = CLIP_SHOW_MS,
    private val files: ConsoleFiles = ConsoleFiles.None,
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
    private var clipShown: String? = null
    private var clipUntil = 0L
    private var offered: OfferedFile? = null
    private var offerId = 0
    private var receiving: String? = null
    private var received: String? = null
    private val usedCodes = HashSet<String>()

    /** One listener's endpoint: once the session has moved on to another, it answers nothing and ends its loop. */
    private inner class Generation : HandoffEndpoint {
        override fun isLive(): Boolean {
            tick()
            return synchronized(this@RemoteInput) { generation === this }
        }

        override fun handle(method: String, target: String, body: String, peer: String): HandoffReply =
            respond(this, method, target, body, peer)

        override fun maxBody(target: String): Int = maxBodyFor(target)

        override fun receive(method: String, target: String, length: Long?, peer: String): HandoffUpload? =
            judgeUpload(this, method, target, length, peer)
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
        publish(null)
    }

    /** The person says it does not: the listener is replaced and pairing starts over. */
    @Synchronized
    fun refuse() {
        if (closed || code == null || paired) return
        listenAgain(refused = true)
    }

    /**
     * Offers [file] to the phone as a download link on its page, replacing any earlier offer; false when the session is
     * over. The link works for this session only: a refusal, the end, or [withdrawOffer] takes it away, and a
     * replaced offer's old link answers nothing.
     */
    @Synchronized
    fun offer(file: OfferedFile): Boolean {
        if (!started || closed) return false
        offered = file
        offerId++
        if (paired) publish(null)
        return true
    }

    /** Takes the offered file away; its link answers nothing from now on. */
    @Synchronized
    fun withdrawOffer() {
        if (offered == null) return
        offered = null
        if (!closed && paired) publish(null)
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
            clipShown != null && now >= clipUntil -> {
                clipShown = null
                (state.value as? RemoteInputState.Paired)?.let { state.value = it.copy(clip = null) }
            }
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
        clipShown = null
        offered = null
        receiving = null
        received = null
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
        clipShown = null
        offered = null
        receiving = null
        received = null
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

        class Status(val offer: String) : Verdict

        class ClipPut(val text: String) : Verdict

        data object ClipGet : Verdict

        class Download(val file: OfferedFile) : Verdict
    }

    private fun respond(from: Generation, method: String, target: String, body: String, peer: String): HandoffReply {
        val verdict = synchronized(this) { if (generation === from) judge(method, target, body, peer) else Verdict.Answer(NOT_FOUND) }
        return when (verdict) {
            is Verdict.Answer -> verdict.reply
            is Verdict.Status -> status(verdict.offer)
            is Verdict.Edit -> deliver(verdict)
            is Verdict.ClipPut -> putClip(verdict.text)
            Verdict.ClipGet -> getClip()
            is Verdict.Download -> download(from, verdict.file)
        }
    }

    private fun judge(method: String, target: String, body: String, peer: String): Verdict {
        if (closed) return Verdict.Answer(GONE)
        val parts = target.substringBefore('?').split('/')
        // "", "r", token [, "state" | "in" | "clipput" | "clipget" | "put"], or "", "r", token, "get", id
        val shaped = parts.size in 3..4 || (parts.size == 5 && parts[3] == "get")
        if (!shaped || parts[0] != "" || parts[1] != "r" || !sameSecret(parts[2], token)) return Verdict.Answer(NOT_FOUND)
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
            method == "GET" && what == "state" -> if (paired) Verdict.Status(offerJson()) else Verdict.Answer(json("""{"state":"confirming"}"""))
            method == "GET" && what == "get" && parts.size == 5 -> judgeDownload(parts[4], target, now)
            method == "POST" && what == "in" -> judgeEdit(body, now)
            method == "POST" && what == "clipput" -> judgeClip(body, now, put = true)
            method == "POST" && what == "clipget" -> judgeClip(body, now, put = false)
            else -> Verdict.Answer(NOT_FOUND)
        }
    }

    /** The one rule every request after the page itself follows: the console has confirmed, and the request carries the code. */
    private fun notAllowed(form: Map<String, String>): HandoffReply? = when {
        !paired -> HandoffReply(403, page("Confirm the code on the console first."))
        !sameSecret(form["code"].orEmpty(), code.orEmpty()) -> HandoffReply(403, page("Wrong code."))
        else -> null
    }

    private fun judgeEdit(body: String, now: Long): Verdict {
        val form = parseForm(body)
        notAllowed(form)?.let { return Verdict.Answer(it) }
        val back = form["back"]?.toIntOrNull() ?: 0
        val text = form["text"].orEmpty()
        if (back !in 0..MAX_TEXT || text.length > MAX_TEXT || text.any { it.isISOControl() }) return Verdict.Answer(HandoffReply(400, ""))
        lastActivity = now
        return Verdict.Edit(back, text, form["enter"] == "1")
    }

    /** A clipboard request is an edit's equal: paired, the code in the body, and it counts as the session being used. */
    private fun judgeClip(body: String, now: Long, put: Boolean): Verdict {
        val form = parseForm(body)
        notAllowed(form)?.let { return Verdict.Answer(it) }
        lastActivity = now
        if (!put) return Verdict.ClipGet
        val text = form["text"].orEmpty()
        return when {
            text.any { it == '\u0000' } -> Verdict.Answer(HandoffReply(400, ""))
            text.isEmpty() -> Verdict.Answer(clipFailure(200, NOTHING_TO_SEND))
            !ClipRules.fits(text) -> Verdict.Answer(clipFailure(413, CLIP_TOO_LARGE))
            else -> Verdict.ClipPut(text)
        }
    }

    /** The offered file as the page's poll learns of it: `null`, or its link's id, name and size in words. */
    private fun offerJson(): String {
        val file = offered ?: return "null"
        return """{"id":$offerId,"name":${jsonString(FileNames.sanitise(file.name))},"size":${jsonString(sizeText(file.size))}}"""
    }

    /** A download is a GET with the code in its query, since a link cannot carry a body; the id is the current offer's only. */
    private fun judgeDownload(id: String, target: String, now: Long): Verdict {
        notAllowed(parseForm(target.substringAfter('?', "")))?.let { return Verdict.Answer(it) }
        val file = offered
        if (file == null || id != offerId.toString()) return Verdict.Answer(NOT_FOUND)
        lastActivity = now
        return Verdict.Download(file)
    }

    private fun download(from: Generation, file: OfferedFile): HandoffReply {
        val stream = try {
            file.open()
        } catch (e: IOException) {
            return HandoffReply(404, page(CANNOT_OPEN))
        } catch (e: SecurityException) {
            return HandoffReply(404, page(CANNOT_OPEN))
        }
        return HandoffReply(
            200, "", "application/octet-stream",
            HandoffDownload(FileNames.sanitise(file.name), file.size, LiveInput(from, stream)),
        )
    }

    /**
     * What the server thread does with a request whose body would be a file, decided from its head alone under the
     * lock, before a byte of the body is read: null for anything that is not `POST /r/<token>/put` with the right token (it
     * is then an ordinary request, with the small body limit and the same empty 404), else a refusal, or the go-ahead to
     * stream. The token is checked first, so only a request that has it gets past the body cap, and the pairing, the
     * paired device, the code (in the query, since the body is the file), a Content-Length and the 4 GB limit
     * are all checked before it is read.
     */
    private fun judgeUpload(from: Generation, method: String, target: String, length: Long?, peer: String): HandoffUpload? {
        val parts = target.substringBefore('?').split('/')
        val name = synchronized(this) {
            val mine = generation === from && !closed && method == "POST" && parts.size == 4 && parts[0] == "" && parts[1] == "r" &&
                parts[3] == "put" && sameSecret(parts[2], token)
            if (!mine) return null
            val refusal = when {
                device == null -> HandoffReply(403, page("Open the address from the console first."))
                device != peer -> TAKEN
                else -> null
            }
            if (refusal != null) return HandoffUpload.Refuse(refusal)
            val form = parseForm(target.substringAfter('?', ""))
            notAllowed(form)?.let { return HandoffUpload.Refuse(it) }
            if (length == null || length < 0) return HandoffUpload.Refuse(HandoffReply(411, ""))
            if (length > FileNames.MAX_BYTES) return HandoffUpload.Refuse(fileFailure(413, FILE_TOO_LARGE))
            lastActivity = clock()
            FileNames.sanitise(form["name"].orEmpty())
        }
        return HandoffUpload.Accept { body -> receiveFile(from, name, length ?: 0L, body) }
    }

    /**
     * Streams one file to disk: into a new file in the person's folder, chunk by chunk, and only [length] bytes can
     * ever arrive. It is kept only when all of it did; any other end (the phone gave up, the session ended, the disk
     * said no) deletes what was written. Runs on the server's thread, outside the lock.
     */
    private fun receiveFile(from: Generation, wanted: String, length: Long, body: InputStream): HandoffReply {
        val name = FileNames.unique(wanted) { files.exists(it) }
            ?: return fileFailure(200, TOO_MANY_OF_NAME)
        val incoming = runCatching { files.create(name) }.getOrNull()
        if (incoming == null) {
            noteFile(from, null, "Could not receive $name: $NO_FOLDER")
            return fileFailure(200, FILE_NOT_STORED)
        }
        val label = "${incoming.name} (${sizeText(length)})"
        synchronized(this) {
            if (generation === from && !closed && paired) {
                receiving = label
                publish(null)
            }
        }
        val input = LiveInput(from, body)
        val buffer = ByteArray(CHUNK)
        var copied = 0L
        var why: String? = null
        while (why == null) {
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                why = INTERRUPTED
                break
            }
            if (n < 0) break
            try {
                incoming.out.write(buffer, 0, n)
            } catch (e: IOException) {
                why = DISK_FAILED
                break
            }
            copied += n
        }
        if (why == null && copied != length) why = INTERRUPTED
        if (why == null) {
            try {
                incoming.out.flush()
                incoming.finish()
            } catch (e: IOException) {
                why = DISK_FAILED
            }
        }
        if (why != null) runCatching { incoming.discard() }
        val line = if (why == null) "Phone sent $label" else "Could not receive ${incoming.name}: $why"
        noteFile(from, if (why == null) line else null, if (why == null) null else line)
        return if (why == null) {
            json("""{"ok":true,"name":${jsonString(incoming.name)},"size":$copied}""")
        } else {
            fileFailure(200, if (why == DISK_FAILED) FILE_NOT_STORED else FILE_INCOMPLETE)
        }
    }

    /** The end of a transfer for the console: the line that says it arrived, or the notice that it did not; an upload counts as use. */
    private fun noteFile(from: Generation, arrived: String?, notice: String?) {
        synchronized(this) {
            if (generation !== from || closed) return
            lastActivity = clock()
            receiving = null
            if (arrived != null) received = arrived
            if (paired) publish(notice)
        }
    }

    /**
     * [source] for as long as this listener's session lives: once it has ended, been closed or moved on to a new
     * listener, a read fails, so closing the session stops a transfer in the middle. Reaching the end of the file counts
     * as the session being used.
     */
    private inner class LiveInput(private val from: Generation, private val source: InputStream) : InputStream() {
        private fun check() {
            val alive = synchronized(this@RemoteInput) { generation === from && !closed }
            if (!alive) throw IOException("The session ended")
        }

        override fun read(): Int {
            check()
            return source.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check()
            val n = source.read(buffer, offset, length)
            if (n < 0) synchronized(this@RemoteInput) { if (generation === from && !closed) lastActivity = clock() }
            return n
        }

        override fun close() = source.close()
    }

    /** Body limit for a request: a clipboard send may be large, but only once its token is right; everything else is small. */
    @Synchronized
    private fun maxBodyFor(target: String): Int {
        val parts = target.substringBefore('?').split('/')
        val clip = !closed && parts.size == 4 && parts[1] == "r" && sameSecret(parts[2], token) && parts[3] == "clipput"
        return if (clip) ClipRules.MAX_BODY else HandoffServer.MAX_BODY
    }

    private fun putClip(text: String): HandoffReply {
        val written = clipboard.write(text)
        synchronized(this) {
            if (written && !closed && paired) {
                clipShown = ClipRules.preview(text)
                clipUntil = clock() + clipShowMs
                publish(null)
            }
        }
        return if (written) json("""{"ok":true}""") else clipFailure(200, CLIP_REFUSED)
    }

    private fun getClip(): HandoffReply = when (val read = clipboard.read()) {
        is ClipRead.Text -> if (ClipRules.fits(read.text)) json("""{"ok":true,"text":${jsonString(read.text)}}""") else clipFailure(200, CLIP_TOO_LARGE)
        ClipRead.Empty -> clipFailure(200, CLIP_EMPTY)
        // Said without a word of what it holds, and never read.
        ClipRead.Sensitive -> clipFailure(200, CLIP_SENSITIVE)
        ClipRead.NotText -> clipFailure(200, CLIP_NOT_TEXT)
        ClipRead.Unavailable -> clipFailure(200, CLIP_UNAVAILABLE)
    }

    /** What the console shows now: the last typing, a notice, and the clip the phone just sent while it is still shown. */
    private fun publish(notice: String?) {
        state.value = RemoteInputState.Paired(
            lastSent, notice, clipShown, receiving, received,
            offered?.let { "${FileNames.sanitise(it.name)} (${sizeText(it.size)})" },
        )
    }

    private fun status(offer: String): HandoffReply = json("""{"state":"paired","secret":${router.secret()},"offer":$offer}""")

    private fun deliver(edit: Verdict.Edit): HandoffReply {
        val delivery = router.deliver(edit.back, edit.text, edit.enter)
        synchronized(this) {
            if (!closed && paired) {
                when (delivery) {
                    is Delivery.Sent -> {
                        lastSent = RemoteInputRouter.forConsole(describe(edit), delivery.secret)
                        publish(null)
                    }
                    is Delivery.Refused -> publish(delivery.notice)
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
        const val CLIP_SHOW_MS = 8_000L
        private const val MAX_TEXT = 1024
        private const val CHUNK = 64 * 1024

        const val FILE_TOO_LARGE = "That file is over 4 GB, the limit for sending files"
        const val FILE_NOT_STORED = "The console could not store the file. Choose another folder on the console and try again"
        const val FILE_INCOMPLETE = "The file did not arrive in full, so the console kept none of it"
        const val TOO_MANY_OF_NAME = "The console already has too many files of that name"
        const val NO_FOLDER = "the folder for received files cannot be written to"
        const val INTERRUPTED = "the transfer did not finish"
        const val DISK_FAILED = "the console could not write it (is the storage full?)"
        const val CANNOT_OPEN = "The console could not open that file."

        const val NOTHING_TO_SEND = "There is no text to send"
        const val CLIP_TOO_LARGE = "That is more than 64 KB of text, the limit for the shared clipboard"
        const val CLIP_REFUSED = "The console would not take it"
        const val CLIP_EMPTY = "The console's clipboard is empty"
        const val CLIP_SENSITIVE = "The console's clipboard holds something marked sensitive, so it is not sent"
        const val CLIP_NOT_TEXT = "The console's clipboard holds something other than text"
        const val CLIP_UNAVAILABLE = "Android did not let droidtop read the console's clipboard. Bring droidtop to the front on the console and try again"

        val NOT_FOUND = HandoffReply(404, "")
        private val GONE = HandoffReply(410, page("This session has ended."))
        private val TAKEN = HandoffReply(409, page("Another device is already connected to this session."))

        /** Equality that takes the same time wherever the two differ. */
        fun sameSecret(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        /** "482913" as the console and the phone say it: "482 913". */
        fun spaced(code: String): String = code.chunked(3).joinToString(" ")

        private fun json(body: String, status: Int = 200) = HandoffReply(status, body, "application/json")

        private fun fileFailure(status: Int, notice: String) = json("""{"ok":false,"notice":${jsonString(notice)}}""", status)

        private fun sizeText(size: Long): String = if (size < 0) "size unknown" else FileNames.size(size)

        private fun clipFailure(status: Int, notice: String) = json("""{"ok":false,"notice":${jsonString(notice)}}""", status)

        /** [text] as a JSON string literal; `<` and the two line separators are escaped too, so it is safe in a script. */
        fun jsonString(text: String): String {
            val out = StringBuilder("\"")
            for (c in text) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c < ' ' || c == '<' || c == '\u2028' || c == '\u2029' -> out.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    else -> out.append(c)
                }
            }
            return out.append('"').toString()
        }

        private fun page(message: String) = RemoteInputPage.message(message)

        private fun parseForm(body: String): Map<String, String> =
            body.split('&').filter { it.contains('=') }.associate {
                val (k, v) = it.split('=', limit = 2)
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
    }
}
