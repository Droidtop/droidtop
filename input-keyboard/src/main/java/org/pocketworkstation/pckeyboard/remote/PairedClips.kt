package org.pocketworkstation.pckeyboard.remote

import org.pocketworkstation.pckeyboard.ClipboardHistory

/**
 * A clip a paired device put on this device's clipboard (the phone page's Send to console, a KDE Connect device's
 * clipboard) goes onto the keyboard's clipboard panel (docs/SPEC.md, "Type from another device", "On the keyboard",
 * Droidtop/tracker#903), under the history's own rules and no others: nothing while the history is off or incognito,
 * nothing while the keyboard serves a password or no-learning field ([fieldSensitive], IncognitoRules), and never a
 * clip marked sensitive ([markedSensitive]). Neither the page nor KDE Connect can mark one today, so it is false from
 * both; it is kept so a source that can is never stored. No Android, so it is unit-tested.
 */
object PairedClips {
    /** Records [text] in [history] when the rules allow; true when it was kept. */
    fun keep(
        history: ClipboardHistory,
        text: String,
        now: Long,
        historyOn: Boolean,
        incognito: Boolean,
        fieldSensitive: Boolean,
        markedSensitive: Boolean = false,
    ): Boolean = historyOn && history.add(text, now, sensitive = markedSensitive || fieldSensitive, incognito = incognito)
}
