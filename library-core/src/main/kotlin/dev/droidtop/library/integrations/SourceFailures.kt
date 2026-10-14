package dev.droidtop.library.integrations

/**
 * A download source's failure in words a person reads (docs/SPEC.md 12a "One
 * search", Droidtop/tracker#375, #485). A source reports whatever its code
 * saw: a site's own page text ("JavaScript is disabled. For a better
 * experience..."), a platform exception, a socket error. None of that belongs
 * on a list row. [plain] names what the person can do about it; [technical]
 * keeps what the source said, stripped of markup and bounded, for the
 * "Technical details" row behind the sentence.
 */
object SourceFailures {
    const val SIGN_IN = "This site needs a sign-in in the browser"
    const val SLOW_DOWN = "The site asked for a pause. Try again in a minute"
    const val OFFLINE = "It could not reach the site. Check the connection and try again"
    const val NOT_READY = "This source is not ready to run here yet"
    const val UNREADABLE = "The site answered in a way droidtop could not read"
    const val TIMED_OUT = "It did not answer in time; try again in a moment"
    const val GENERIC = "It did not answer. Try again in a moment"

    /** A reason that is a short plain sentence already is left as it is. */
    private const val PLAIN_MAX_CHARS = 140
    private const val TECHNICAL_MAX_CHARS = 400

    private val STATUS_CODE = Regex("\\b(401|403)\\b")
    private val TOO_MANY = Regex("\\b429\\b")
    private val LOG_IN = Regex("\\b(log ?in|sign ?in)\\b")
    private val TLS = Regex("\\bssl\\b")
    private val MARKUP = Regex("<[^>]*>")
    private val SPACES = Regex("\\s+")

    private val SIGN_IN_WORDS = listOf(
        "javascript is disabled", "enable javascript", "just a moment", "checking your browser", "attention required",
        "verify you are human", "captcha", "cloudflare", "access denied", "forbidden", "unauthorized",
    )
    private val OFFLINE_WORDS = listOf(
        "unable to resolve host", "unknownhost", "no address associated", "failed to connect", "network is unreachable",
        "connection refused", "connection reset", "handshake", "unexpected end of stream",
    )
    private val DEVELOPER_WORDS = listOf("exception", "channel-error", "stacktrace", "traceback", "error:", "null pointer", "lateinit")

    fun plain(raw: String?): String {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return GENERIC
        val lower = text.lowercase()
        return when {
            SIGN_IN_WORDS.any { it in lower } || STATUS_CODE.containsMatchIn(lower) || LOG_IN.containsMatchIn(lower) -> SIGN_IN
            "too many requests" in lower || "rate limit" in lower || TOO_MANY.containsMatchIn(lower) -> SLOW_DOWN
            "did not answer in time" in lower || "timed out" in lower || "timeout" in lower -> TIMED_OUT
            OFFLINE_WORDS.any { it in lower } || TLS.containsMatchIn(lower) -> OFFLINE
            DEVELOPER_WORDS.any { it in lower } -> NOT_READY
            MARKUP.containsMatchIn(text) -> UNREADABLE
            text.length > PLAIN_MAX_CHARS || '\n' in text -> UNREADABLE
            else -> text
        }
    }

    /** What the source said, as one bounded line of text with any markup removed. */
    fun technical(raw: String?): String? {
        val text = raw.orEmpty().replace(MARKUP, " ").replace(SPACES, " ").trim()
        if (text.isEmpty()) return null
        return if (text.length > TECHNICAL_MAX_CHARS) text.take(TECHNICAL_MAX_CHARS - 1) + "…" else text
    }
}
