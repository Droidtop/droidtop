package dev.droidtop.runtime

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The times `logs --since` and `--until` take (Droidtop/tracker#715), read as Docker's and Podman's clients and APIs
 * send them: Unix seconds with an optional fraction ("1698581234.5"), an RFC 3339 time, a date or a date and time
 * without a zone (UTC, as Docker reads them), or a Go duration back from now ("10m", "1h30m", "1.5s").
 */
object EngineTime {
    private val DURATION = Regex("""(\d+(?:\.\d+)?)(ns|us|µs|ms|s|m|h)""")

    /** [value] as milliseconds since the epoch; null for one that is none of the forms. */
    fun parse(value: String, nowMs: Long): Long? {
        val v = value.trim()
        if (v.isEmpty()) return null
        if (v.matches(Regex("""\d+(\.\d+)?"""))) {
            val seconds = v.substringBefore('.').toLongOrNull() ?: return null
            val fraction = v.substringAfter('.', "").take(3).padEnd(3, '0').toLong()
            return seconds * 1000 + fraction
        }
        duration(v)?.let { return nowMs - it }
        return runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(v).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    /** A Go duration such as "1h30m" in milliseconds; null when [value] is not one, whole. */
    internal fun duration(value: String): Long? {
        var at = 0
        var ms = 0.0
        while (at < value.length) {
            val m = DURATION.find(value, at)?.takeIf { it.range.first == at } ?: return null
            val n = m.groupValues[1].toDouble()
            ms += n * when (m.groupValues[2]) {
                "h" -> 3_600_000.0
                "m" -> 60_000.0
                "s" -> 1_000.0
                "ms" -> 1.0
                "us", "µs" -> 0.001
                else -> 0.000001
            }
            at = m.range.last + 1
        }
        return if (at == 0) null else ms.toLong()
    }
}
