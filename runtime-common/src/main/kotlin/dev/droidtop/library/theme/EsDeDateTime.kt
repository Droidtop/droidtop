package dev.droidtop.library.theme

/**
 * Real `DateTimeComponent::getDisplayString` (DateTimeComponent.cpp:86-135)
 * for the non-clock case, as pure Kotlin -- no Android, no Compose, no
 * `java.text`, so it is directly unit-testable the same way
 * [EsDeVideoLayout]/[EsDeTextContainer] are.
 *
 * Splitting it this way is what the real C++ does too: everything below is
 * decided BEFORE any strftime call, and only the [Formatted] branch ever
 * reaches one.
 */
sealed interface EsDeDateTimeDisplay {
    /** Show this exact string -- ES-DE's "never"/"unknown", the theme's own `defaultValue`, or a relative phrase. */
    data class Literal(val text: String) : EsDeDateTimeDisplay

    /** Format this epoch-seconds value through the element's own `format` property. */
    data class Formatted(val epochSeconds: Long) : EsDeDateTimeDisplay
}

/**
 * ES-DE's own Unix-epoch-across-timezones guard (DateTimeComponent.cpp:92-93):
 * a "never played" value is stored as epoch 0 but read back through the
 * local timezone, so anything inside the first 23 hours is treated as
 * "never" rather than as a real timestamp. 60 * 60 * 23.
 */
const val ES_DE_EPOCH_GUARD_SECONDS: Long = 82800

/**
 * Real relative/absolute display decision.
 *
 * [epochSeconds] is the metadata value as real ES-DE stores it: 0 for
 * "no value at all" (its MD_DATE default is "19700101T000000", which is
 * epoch 0), never null. [nowSeconds] is passed in rather than read here so
 * the whole function stays pure.
 *
 * [defaultValue] is the element's own already-resolved `defaultValue`
 * property -- ES-DE substitutes a single blankspace for the literal
 * `:space:` token at parse time (DateTimeComponent.cpp:354-357), so that
 * translation belongs to the caller, not here.
 */
fun esDeDateTimeDisplay(
    epochSeconds: Long,
    nowSeconds: Long,
    displayRelative: Boolean,
    defaultValue: String?,
): EsDeDateTimeDisplay {
    if (displayRelative) {
        // DateTimeComponent.cpp:91-99.
        if (epochSeconds < ES_DE_EPOCH_GUARD_SECONDS) {
            return EsDeDateTimeDisplay.Literal(if (defaultValue.isNullOrEmpty()) "never" else defaultValue)
        }
        // DateTimeComponent.cpp:101-102. ES-DE builds its Duration from an
        // unsigned int, so a clock skew putting `now` behind the stored
        // time would underflow into a nonsense span there; droidtop floors
        // the difference at zero instead, which renders "0 seconds ago" --
        // the one deliberate divergence in this function.
        val total = (nowSeconds - epochSeconds).coerceAtLeast(0)
        // Utils::Time::Duration's own decomposition, TimeUtil.cpp:73-80.
        val days = total / 86400
        val hours = (total % 86400) / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        // DateTimeComponent.cpp:106-122, including its plural forms.
        val text = when {
            days > 0 -> plural(days, "day")
            hours > 0 -> plural(hours, "hour")
            minutes > 0 -> plural(minutes, "minute")
            else -> plural(seconds, "second")
        }
        return EsDeDateTimeDisplay.Literal(text)
    }
    // DateTimeComponent.cpp:126-131.
    if (epochSeconds == 0L) {
        return EsDeDateTimeDisplay.Literal(if (defaultValue.isNullOrEmpty()) "unknown" else defaultValue)
    }
    return EsDeDateTimeDisplay.Formatted(epochSeconds)
}

private fun plural(count: Long, unit: String): String =
    if (count == 1L) "$count $unit ago" else "$count ${unit}s ago"

/**
 * Real `displayRelative` resolution (DateTimeComponent.cpp:368-372):
 * `metadata=lastplayed` turns it ON implicitly, and an explicit
 * `displayRelative` property then overrides that either way. A theme
 * writing `<displayRelative>false</displayRelative>` on a lastplayed
 * element really does get an absolute date.
 */
fun esDeDisplayRelative(metadata: String?, declared: Boolean?): Boolean =
    declared ?: (metadata == "lastplayed")
