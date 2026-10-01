package dev.droidtop.library.scraper

/** Pure quota policy for the anonymous ScreenScraper tier. */
internal class ScreenScraperPacing(private val minimumIntervalMs: Long = 11_000L) {
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
