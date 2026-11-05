package dev.droidtop.library.settings

import android.content.Context
import dev.droidtop.runtime.R

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
    const val ID_REMIND = "pref_session_remind"
    const val ID_CLOSE = "pref_session_close"

    private fun options(choices: List<Int>) = choices.map { ChoiceOption(it.toString(), PlayTime.limitLabel(it)) }
    private fun timerOptions(choices: List<Int>) =
        choices.map { ChoiceOption(it.toString(), if (it <= 0) words(R.string.play_time_off) else PlayTime.duration(it * 60L)) }

    fun screen() = CatalogScreen(
        id = "play_time",
        title = words(R.string.play_time_play_time),
        subtitle = words(R.string.play_time_limits_for_kid_mode),
        groups = { context -> groups(context) },
    )

    /** The row that opens the page; its value is today's and this week's play against the limits. */
    fun link() = NestedScreenItem(
        id = ID_LINK,
        title = words(R.string.play_time_play_time),
        subtitle = words(R.string.play_time_daily_and_weekly_limits_for),
        inline = screen(),
        valueLabel = { context ->
            val (limits, today, week) = PlayTimePrefs.snapshot(context)
            if (limits.any) PlayTime.summary(limits, today, week) else "No limit"
        },
    )

    private fun groups(context: Context): List<CatalogGroup> {
        val (limits, today, week) = PlayTimePrefs.snapshot(context)
        val timers = PlayTimePrefs.sessionTimers(context)
        return listOf(
            // Outside Kid mode, for the person's own play (Droidtop/tracker#721); Kid mode keeps the limits below.
            CatalogGroup(
                id = "play_time_session",
                title = words(R.string.play_time_this_session),
                items = listOf(
                    ChoiceItem(
                        id = ID_REMIND,
                        title = words(R.string.play_time_remind_me_every),
                        subtitle = words(R.string.play_time_remind_subtitle),
                        options = timerOptions(SessionTimers.REMIND_CHOICES),
                        current = timers.remindMinutes.toString(),
                        onSelect = { ctx, value -> PlayTimePrefs.setRemind(ctx, value.toIntOrNull() ?: 0) },
                    ),
                    ChoiceItem(
                        id = ID_CLOSE,
                        title = words(R.string.play_time_close_the_game_after),
                        subtitle = words(R.string.play_time_close_subtitle),
                        options = timerOptions(SessionTimers.CLOSE_CHOICES),
                        current = timers.closeMinutes.toString(),
                        onSelect = { ctx, value -> PlayTimePrefs.setClose(ctx, value.toIntOrNull() ?: 0) },
                    ),
                ),
            ),
            CatalogGroup(
                id = "play_time_limits",
                title = words(R.string.play_time_kid_mode),
                items = listOf(
                    ChoiceItem(
                        id = ID_DAILY,
                        title = words(R.string.play_time_each_day),
                        subtitle = words(R.string.play_time_all_games_together),
                        options = options(PlayTime.DAILY_CHOICES),
                        current = limits.dailyMinutes.toString(),
                        onSelect = { ctx, value -> PlayTimePrefs.setDaily(ctx, value.toIntOrNull() ?: 0) },
                    ),
                    ChoiceItem(
                        id = ID_WEEKLY,
                        title = words(R.string.play_time_each_week),
                        subtitle = words(R.string.play_time_from_monday),
                        options = options(PlayTime.WEEKLY_CHOICES),
                        current = limits.weeklyMinutes.toString(),
                        onSelect = { ctx, value -> PlayTimePrefs.setWeekly(ctx, value.toIntOrNull() ?: 0) },
                    ),
                    TextBlockItem(
                        id = "play_time_used",
                        title = words(R.string.play_time_played_in_kid_mode),
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
