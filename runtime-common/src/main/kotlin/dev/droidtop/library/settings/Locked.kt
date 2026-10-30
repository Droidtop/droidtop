package dev.droidtop.library.settings

import android.content.Context

/**
 * Why a control is read-only here and how to change that (docs/SPEC.md "The companion's tabs", Droidtop/tracker#786
 * rule 3: "Rows never vanish because a grant or the helper is missing"). The row stays where it is and shows its
 * state; a tap opens one plain sheet that says what droidtop would be allowed to change ([allows]), with one button
 * for the next step ([step]) and, where Android has a page for it, that page as a second button ([android]). One
 * model for every page, so there is no per-page "More controls" line.
 */
data class Locked(val allows: String, val step: LockedStep, val android: LockedStep? = null)

/** One button of a [Locked] sheet: what it says and what a press does (it may start an activity, so it gets a context). */
data class LockedStep(val label: String, val run: (Context) -> Unit)
