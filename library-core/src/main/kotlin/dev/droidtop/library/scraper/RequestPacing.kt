package dev.droidtop.library.scraper

/**
 * Pure request pacing for a source that limits how often it is asked: a minimum gap between requests and, for
 * ScreenScraper's anonymous tier, a growing back-off after a quota answer ([response]). The Steam store uses the gap only.
 */
internal class RequestPacing(private val minimumIntervalMs: Long = 11_000L) {
    private var lastRequestAt = Long.MIN_VALUE
    private var backoffUntil = 0L
    private var failures = 0

    @Synchronized
    fun delayBeforeRequest(now: Long): Long {
        val intervalDeadline = if (lastRequestAt == Long.MIN_VALUE) now else lastRequestAt + minimumIntervalMs
        return maxOf(0L, maxOf(intervalDeadline, backoffUntil) - now)
    }

    @Synchronized
    fun requestStarted(at: Long) { lastRequestAt = at }

    @Synchronized
    fun response(status: Int, body: String, now: Long) {
        val quota = status == 429 || status == 430 || body.contains("quota", ignoreCase = true) ||
            body.contains("too many requests", ignoreCase = true)
        if (!quota) {
            if (status in 200..299) failures = 0
            return
        }
        failures = (failures + 1).coerceAtMost(8)
        val delay = (60_000L shl (failures - 1)).coerceAtMost(6 * 60 * 60 * 1000L)
        backoffUntil = maxOf(backoffUntil, now + delay)
    }
}
