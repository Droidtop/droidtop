package dev.droidtop.library.settings

import android.content.Context

/**
 * The Play time page of Settings (docs/SPEC.md 7f "Play-time limits", Droidtop/tracker#421): the daily and
 * the weekly limit for Kid mode, and what has been played so far. One page, built here in `:runtime-common`
 * beside the other Gaming settings; the limits themselves are [PlayTimePrefs] and are enforced by `:app`'s
 * play-time limiter. Hidden in Kid, Kiosk and Guest like the other items that control the restriction
 * ([ControlAccess]).
 */
object PlayTimeSettings {
    const val ID_LINK = "pref_play_time"
    const val ID_DAILY = "pref_play_limit_daily"
    const val ID_WEEKLY = "pref_play_limit_weekly"

    private fun options(choices: List<Int>) = choices.map { ChoiceOption(it.toString(), PlayTime.limitLabel(it)) }

    fun screen() = CatalogScreen(
        id = "play_time",
        title = "Play time",
        subtitle = "Limits for Kid mode",
        groups = { context -> groups(context) },
    )

    /** The row that opens the page; its value is today's and this week's play against the limits. */
    fun link() = NestedScreenItem(
        id = ID_LINK,
        title = "Play time",
        subtitle = "Daily and weekly limits for Kid mode",
        inline = screen(),
        valueLabel = { context ->
            val (limits, today, week) = PlayTimePrefs.snapshot(context)
            if (limits.any) PlayTime.summary(limits, today, week) else "No limit"
        },
    )

    private fun groups(context: Context): List<CatalogGroup> {
        val (limits, today, week) = PlayTimePrefs.snapshot(context)
        return listOf(
            CatalogGroup(
                id = "play_time_limits",
                title = null,
                items = listOf(
                    ChoiceItem(
                        id = ID_DAILY,
                        title = "Each day",
                        subtitle = "All games together",
                        options = options(PlayTime.DAILY_CHOICES),
                        current = limits.dailyMinutes.toString(),
                        onSelect = { ctx, value -> PlayTimePrefs.setDaily(ctx, value.toIntOrNull() ?: 0) },
                    ),
                    ChoiceItem(
                        id = ID_WEEKLY,
                        title = "Each week",
                        subtitle = "From Monday",
                        options = options(PlayTime.WEEKLY_CHOICES),
                        current = limits.weeklyMinutes.toString(),
                        onSelect = { ctx, value -> PlayTimePrefs.setWeekly(ctx, value.toIntOrNull() ?: 0) },
                    ),
                    TextBlockItem(
                        id = "play_time_used",
                        title = "Played in Kid mode",
                        text = PlayTime.summary(limits, today, week),
                    ),
                    TextBlockItem(
                        id = "play_time_how",
                        text = "Five minutes before a limit droidtop warns. At the limit the game closes after " +
                            "${PlayTime.GRACE_SECONDS} seconds.",
                    ),
                ),
            ),
        )
    }
}
