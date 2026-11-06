package org.pocketworkstation.pckeyboard

import java.text.Normalizer
import java.util.Locale

/*
 * The clipboard panel's rules (docs/SPEC.md 6a, "Clipboard history", Droidtop/tracker#903 H12d), with no Android
 * classes so they are tested on the JVM. Ported in part, each piece naming its origin below (NOTICE.md):
 *
 * - HeliBoard, https://github.com/Helium314/HeliBoard at 2d9869b2, GPL-3.0-only, Copyright (C) HeliBoard contributors:
 *   the retention rule of `latin/database/ClipboardDao.clearOldClips` (an unpinned clip older than the limit goes, a
 *   pinned one never does) and its timing in `ClipboardHistoryManager.prepareClipboardHistory` (checked when the history
 *   is about to be shown, never while it is on screen), as [ClipKeep] and `ClipboardHistory.expire`.
 * - FlorisBoard, https://github.com/florisboard/florisboard at bac346e8, Apache-2.0, Copyright (C) 2025 The FlorisBoard
 *   Contributors: the pinned, recent (the last five minutes) and other grouping of `ime/clipboard/ClipboardHistory.kt`,
 *   as [ClipGroups]. Licensed under the Apache License, Version 2.0; you may not use this part except in compliance
 *   with the License. You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. Distributed on an "AS IS"
 *   BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

/**
 * Whether a clip offered to the history is kept (docs/SPEC.md 6a, "Clipboard history"). The one rule every source goes
 * through: the system clipboard's listener, the keyboard's own writes to the clipboard, and a paired device
 * ("Type from another device"). Nothing is kept while the history is off or incognito; a clip is sensitive when its
 * source marked it (`android.content.extra.IS_SENSITIVE`, which Android 13 and later password managers set and the
 * keyboard sets on what it types through the clipboard) or when the keyboard serves a password or no-learning field
 * ([IncognitoRules.sensitiveField]), since apps before Android 13 never set the mark. A sensitive clip is never stored,
 * and an unpinned copy of the same text kept before is forgotten: the text is now known to be a secret.
 */
object ClipIntake {
    enum class Outcome(val changed: Boolean) {
        KEPT(true),
        DROPPED(false),

        /** Not kept, and an unpinned entry with the same text was forgotten. */
        FORGOT(true),
    }

    fun keep(
        history: ClipboardHistory,
        text: String,
        now: Long,
        historyOn: Boolean,
        incognito: Boolean,
        fieldSensitive: Boolean,
        markedSensitive: Boolean,
    ): Outcome {
        if (markedSensitive || fieldSensitive) return if (history.forgetUnpinned(text)) Outcome.FORGOT else Outcome.DROPPED
        if (!historyOn) return Outcome.DROPPED
        return if (history.add(text, now, incognito = incognito)) Outcome.KEPT else Outcome.DROPPED
    }
}

/**
 * "Keep clips for" (`pref_clipboard_keep`): how long an unpinned clip stays. Pinned clips stay whatever it says. A
 * missing or unknown value is [FOREVER], what the history did before the setting existed, so nobody's history changes
 * until they pick a limit.
 */
enum class ClipKeep(val prefValue: String, val millis: Long?) {
    HOUR("hour", 60L * 60 * 1000),
    DAY("day", 24L * 60 * 60 * 1000),
    WEEK("week", 7L * 24 * 60 * 60 * 1000),
    FOREVER("forever", null);

    companion object {
        @JvmStatic
        fun fromPref(value: String?): ClipKeep = entries.firstOrNull { it.prefValue == value } ?: FOREVER
    }
}

/** The panel's search: every word of the query, in any order, ignoring case and accents. An empty query matches all. */
object ClipSearch {
    fun matches(text: String, query: String): Boolean {
        val words = fold(query).split(' ', '\t', '\n').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = fold(text)
        return words.all { haystack.contains(it) }
    }

    fun filter(entries: List<ClipboardHistory.Entry>, query: String): List<ClipboardHistory.Entry> =
        entries.filter { matches(it.text, query) }

    private val marks = Regex("\\p{Mn}+")

    private fun fold(text: String): String =
        marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
}

/**
 * A clip's age as the panel shows it: "now", "2 min", "3 h" (earlier today), "yesterday", "4 days", "2 wk". Days are
 * counted on the local calendar ([zoneOffsetMs] is the zone's offset from UTC at [now]), so a clip from 23:00 is
 * "yesterday" at 01:00. A time ahead of [now] (the clock was moved back) reads as now.
 */
object ClipAge {
    enum class Unit { NOW, MINUTES, HOURS, YESTERDAY, DAYS, WEEKS }

    data class Age(val unit: Unit, val count: Long)

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun of(at: Long, now: Long, zoneOffsetMs: Long): Age {
        val elapsed = now - at
        if (elapsed < MINUTE) return Age(Unit.NOW, 0)
        if (elapsed < HOUR) return Age(Unit.MINUTES, elapsed / MINUTE)
        val days = Math.floorDiv(now + zoneOffsetMs, DAY) - Math.floorDiv(at + zoneOffsetMs, DAY)
        return when {
            days <= 0L -> Age(Unit.HOURS, elapsed / HOUR)
            days == 1L -> Age(Unit.YESTERDAY, 1)
            days < 7L -> Age(Unit.DAYS, days)
            else -> Age(Unit.WEEKS, days / 7)
        }
    }
}

/** The panel's sections, FlorisBoard's: pinned, then the last five minutes, then the rest; an empty one is left out. */
object ClipGroups {
    enum class Group { PINNED, RECENT, OLDER }

    const val RECENT_MS = 5 * 60_000L

    fun of(entries: List<ClipboardHistory.Entry>, now: Long): List<Pair<Group, List<ClipboardHistory.Entry>>> {
        val pinned = entries.filter { it.pinned }
        val recent = entries.filter { !it.pinned && now - it.at < RECENT_MS }
        val older = entries.filter { !it.pinned && now - it.at >= RECENT_MS }
        return listOf(Group.PINNED to pinned, Group.RECENT to recent, Group.OLDER to older).filter { it.second.isNotEmpty() }
    }
}
