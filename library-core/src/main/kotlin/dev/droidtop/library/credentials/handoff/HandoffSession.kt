package dev.droidtop.library.credentials.handoff

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** One field a phone page may submit: only declared fields are ever accepted. */
data class HandoffField(
    val id: String,
    val label: String,
    val secret: Boolean,
    val maxLength: Int = 256,
)

/**
 * One phone handoff (docs/SPEC.md 7h, "Supplying a key from a phone"): a
 * random one-time token that is the whole address of the page, a lifetime of a
 * few minutes, and the rules for what may be submitted. Pure logic with an
 * injected clock; [HandoffHttp] speaks HTTP for it and [HandoffServer] owns the
 * socket. Nothing is stored here: the values wait in [received] until the
 * person confirms on the console, and are dropped by [close].
 */
class HandoffSession(
    val fields: List<HandoffField>,
    private val clock: () -> Long,
    val ttlMs: Long = DEFAULT_TTL_MS,
    val token: String = newToken(),
    private val maxRequestsPerWindow: Int = 20,
    private val maxBadTokens: Int = 5,
) {
    private val startedAt = clock()
    private val requestTimes = ArrayDeque<Long>()
    private var badTokens = 0
    private var values: Map<String, String>? = null
    private var closed = false

    /** What a request to the page may be answered with, decided before any field is read. */
    enum class Admission { OK, BAD_TOKEN, EXPIRED, USED, RATE_LIMITED }

    sealed interface Submit {
        /** The values passed every check and now wait for the person to confirm. */
        data object Accepted : Submit
        data class Denied(val reason: Admission) : Submit
        /** Field ids that were missing, unknown, empty, too long or held control characters. */
        data class Invalid(val fieldIds: Set<String>) : Submit
    }

    fun expiresAt(): Long = startedAt + ttlMs

    fun remainingMs(): Long = (expiresAt() - clock()).coerceAtLeast(0)

    /** Whether the page should still be served: not closed, not expired, not yet submitted. */
    @Synchronized
    fun isOpen(): Boolean = !closed && values == null && clock() < expiresAt()

    /** Whether the server should still be listening: open, or holding values for the person to confirm. */
    @Synchronized
    fun isLive(): Boolean = !closed && clock() < expiresAt() + REVIEW_GRACE_MS

    /** Counts the request, then judges the token. Every request to the server goes through here first. */
    @Synchronized
    fun admit(candidate: String): Admission {
        val now = clock()
        while (requestTimes.isNotEmpty() && now - requestTimes.first() > WINDOW_MS) requestTimes.removeFirst()
        if (closed) return Admission.EXPIRED
        if (requestTimes.size >= maxRequestsPerWindow) return Admission.RATE_LIMITED
        requestTimes.addLast(now)
        if (!tokenMatches(candidate)) {
            badTokens++
            if (badTokens >= maxBadTokens) closed = true
            return Admission.BAD_TOKEN
        }
        if (now >= expiresAt()) return Admission.EXPIRED
        if (values != null) return Admission.USED
        return Admission.OK
    }

    /** Checks the token (counting the request) and, if it passes, the fields; a second success is refused. */
    @Synchronized
    fun submit(candidate: String, submitted: Map<String, String>): Submit {
        val admission = admit(candidate)
        if (admission != Admission.OK) return Submit.Denied(admission)
        val declared = fields.associateBy { it.id }
        val bad = mutableSetOf<String>()
        for (id in submitted.keys) if (id !in declared) bad += id
        val clean = LinkedHashMap<String, String>()
        for (field in fields) {
            val value = submitted[field.id]?.trim()
            if (value.isNullOrEmpty() || value.length > field.maxLength || value.any { it.isISOControl() }) {
                bad += field.id
            } else {
                clean[field.id] = value
            }
        }
        if (bad.isNotEmpty()) return Submit.Invalid(bad)
        values = clean
        return Submit.Accepted
    }

    /** The accepted values, waiting for the person's confirmation on the console; null before a submit. */
    @Synchronized
    fun received(): Map<String, String>? = values

    /** Ends the session and drops anything received. */
    @Synchronized
    fun close() {
        closed = true
        values = null
    }

    private fun tokenMatches(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))

    companion object {
        const val DEFAULT_TTL_MS = 5 * 60_000L
        private const val WINDOW_MS = 60_000L
        private const val REVIEW_GRACE_MS = 60_000L

        /** 128 bits from the system's secure random, URL-safe. */
        fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** How a received value is shown on the console before it is saved. */
        fun maskForReview(value: String, secret: Boolean): String =
            if (!secret) value else if (value.length <= 8) "•••• (${value.length})" else
                "••••${value.takeLast(4)} (${value.length})"
    }
}
