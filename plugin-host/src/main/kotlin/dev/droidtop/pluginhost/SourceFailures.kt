package dev.droidtop.pluginhost

/**
 * A plugin's failure in words a person reads (docs/SPEC.md 12a "One search",
 * Droidtop/tracker#375, #485): a source's line in the one search, and every
 * error notice a plugin page or action draws ([PluginViewCall.errorNotice]),
 * which is why it lives here and not in the library. A source reports whatever its code
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

    /** A source that answered SIGN_IN_REQUIRED (docs/plugin-api.md 1.3); droidtop's Sign in follows it. */
    const val NEEDS_SIGN_IN = "Sign in to search here"

    /** A source that answered BROWSER_CHECK_REQUIRED; droidtop's Open the site follows it. */
    const val NEEDS_BROWSER_CHECK = "The site checks the browser first"

    /** A source that answered NOT_FOUND: the record it was asked for is gone (docs/plugin-api.md 1.11, point 4). */
    const val GONE = "It is no longer on the site"

    /** A message that carries a link: droidtop never shows a plugin's link as its sentence (Droidtop/tracker#784). */
    const val SEE_DETAILS = "It could not do that. What it said is under Technical details"

    /**
     * The one sentence for a failed answer, by its code first (docs/plugin-api.md 1.3 and 1.11 point 4, Droidtop/tracker#784
     * slice 4): the shared codes are droidtop's words for every source, and only FAILED's own message may stand, through
     * [plain]. A plugin's words never decide what a sign-in, a check, a pause or a missing record says.
     */
    fun forCode(code: PluginErrorCode?, message: String?): String = when (code) {
        PluginErrorCode.SIGN_IN_REQUIRED -> SIGN_IN
        PluginErrorCode.BROWSER_CHECK_REQUIRED -> NEEDS_BROWSER_CHECK
        PluginErrorCode.RATE_LIMITED -> SLOW_DOWN
        PluginErrorCode.NOT_FOUND -> GONE
        PluginErrorCode.PERMISSION_DENIED -> permissionOff(message, code) ?: PERMISSION_OFF
        else -> plain(message)
    }

    /** "[source] could not [what]: [sentence]", the one shape a source's failure is said in (docs/plugin-api.md 1.11, point 4). Pure. */
    fun line(source: String, what: String, code: PluginErrorCode?, message: String?): String =
        "$source could not $what: ${forCode(code, message).replaceFirstChar { if (it.isUpperCase()) it.lowercaseChar() else it }}"

    /**
     * A source refused by droidtop's own permission check (PERMISSION_DENIED, or the broker's sentence passed through:
     * '"<label>" is turned off for <plugin>.' / '<plugin> needs you to allow "<label>".'), as one sentence naming the
     * permission, or null when [raw] is not such a refusal. Its page is where it is turned on, and the search offers it
     * (Droidtop/tracker#485 rig step 5: LewdCorner's line was the broker's own words about its sign-in permission).
     */
    fun permissionOff(raw: String?, code: PluginErrorCode? = null): String? {
        val text = raw.orEmpty()
        val label = TURNED_OFF.find(text)?.groupValues?.get(1) ?: NEEDS_ALLOW.find(text)?.groupValues?.get(1)
        return when {
            label != null -> "It needs \u201c$label\u201d, which is not allowed for it"
            code == PluginErrorCode.PERMISSION_DENIED -> PERMISSION_OFF
            else -> null
        }
    }

    const val PERMISSION_OFF = "It needs a permission that is not allowed for it"
    private val TURNED_OFF = Regex("\"([^\"]{1,80})\" is turned off for ")
    private val NEEDS_ALLOW = Regex("needs you to allow \"([^\"]{1,80})\"")

    /** A reason that is a short plain sentence already is left as it is. */
    private const val PLAIN_MAX_CHARS = 140
    private const val TECHNICAL_MAX_CHARS = 400

    private val STATUS_CODE = Regex("\\b(401|403)\\b")
    private val TOO_MANY = Regex("\\b429\\b")
    private val LOG_IN = Regex("\\b(log ?in|sign ?in)\\b")
    private val TLS = Regex("\\bssl\\b")
    private val MARKUP = Regex("<[^>]*>")

    private val SIGN_IN_WORDS = listOf(
        "javascript is disabled", "enable javascript", "just a moment", "checking your browser", "attention required",
        "verify you are human", "captcha", "cloudflare", "access denied", "forbidden", "unauthorized",
    )
    private val OFFLINE_WORDS = listOf(
        "unable to resolve host", "unknownhost", "no address associated", "failed to connect", "network is unreachable",
        "connection refused", "connection reset", "handshake", "unexpected end of stream",
    )
    private val DEVELOPER_WORDS = listOf("exception", "channel-error", "stacktrace", "traceback", "error:", "null pointer", "lateinit")

    /**
     * [raw] as the person's sentence: droidtop's own for what it recognises, else the source's words when they are one
     * short plain sentence, cleaned ([PluginText]) and carrying no link; anything else is droidtop's too. Pure.
     */
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
            else -> {
                val clean = PluginText.clean(text, PLAIN_MAX_CHARS) ?: return GENERIC
                if (LINK.containsMatchIn(clean)) SEE_DETAILS else clean
            }
        }
    }

    /** A link or an address in a message: a scheme, `www.`, or a host name with a common top-level domain. */
    private val LINK = Regex("(?i)\\b[a-z][a-z0-9+.-]{1,20}://|\\bwww\\.|\\b[a-z0-9-]{1,63}\\.(com|net|org|io|to|zone|ru|cc|me|xyz|co|app|dev|gg|link|site|top)\\b")

    /**
     * "Technical details" for a failed call (Droidtop/tracker#746): what was asked of which plugin, what came back (the
     * error code, or the exception when there was no reply), the source's own words and the time, one fact per line,
     * so it can be copied into a report. A line that would only repeat [headline], the sentence already on the row, is
     * left out; the call and the time always say more than the headline does.
     */
    fun details(
        call: String,
        headline: String?,
        plugin: String? = null,
        code: PluginErrorCode? = null,
        exception: String? = null,
        said: String? = null,
        detail: String? = null,
        at: Long = System.currentTimeMillis(),
    ): String {
        val saidLine = technical(said)?.takeIf { it != headline?.trim() }
        val detailLine = technical(detail)?.takeIf { it != saidLine && it != headline?.trim() }
        return listOfNotNull(
            "Call: $call",
            plugin?.let { "Plugin: $it" },
            code?.let { "Answer: ${it.name}" } ?: exception?.let { "Exception: $it" },
            saidLine?.let { "Said: $it" },
            detailLine?.let { "Detail: $it" },
            "At: ${TIME.format(java.time.Instant.ofEpochMilli(at))} UTC",
        ).joinToString("\n")
    }

    private val TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC)

    /** What the source said, as one bounded line of text, cleaned ([PluginText]): markup, control and invisible characters removed. */
    fun technical(raw: String?): String? = PluginText.clean(raw, TECHNICAL_MAX_CHARS)
}
