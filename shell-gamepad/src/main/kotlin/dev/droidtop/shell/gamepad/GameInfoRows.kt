package dev.droidtop.shell.gamepad

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.achievements.RaAchievement
import dev.droidtop.library.achievements.RaProgress
import dev.droidtop.library.achievements.RaResult
import dev.droidtop.library.achievements.RetroAchievements
import dev.droidtop.library.achievements.RetroAchievementsClient
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One fact a game's page gets from outside the library: its achievements (docs/SPEC.md 7h, "RetroAchievements").
 * The plain detail screen of a console game draws it as a line and, when there is something to open, a chip.
 */
internal data class GameInfoRow(
    val title: String,
    val value: String? = null,
    val subtitle: String? = null,
    /** A page of rows that A or the chip opens (the achievements). */
    val screen: CatalogScreen? = null,
    /** An address that A or the chip opens in the browser. */
    val url: String? = null,
    /** The chip's label on the detail screen, which draws no chip for a row without one. */
    val chip: String? = null,
)

/**
 * The rows for [entry], filled in off the main thread: RetroAchievements (a console game, once the person is
 * signed in). Asked when the page opens, never while a list draws, and cached by [RetroAchievements]. A fact
 * nothing knows is not a row; a failure is one, except being offline, which on a handheld is ordinary and says
 * nothing. [enabled] is false for something that is not a game.
 */
@Composable
internal fun rememberGameInfoRows(entry: LibraryEntry, enabled: Boolean = true): List<GameInfoRow> {
    val context = LocalContext.current.applicationContext
    val rows by produceState(emptyList<GameInfoRow>(), entry.id, enabled) {
        if (!enabled) return@produceState
        value = withContext(Dispatchers.IO) { achievementRows(context, entry) }
    }
    return rows
}

private fun offline(message: String) = message.startsWith("No connection")

internal fun achievementRows(context: Context, entry: LibraryEntry): List<GameInfoRow> =
    when (val result = RetroAchievements.lookup(context, entry)) {
        RaResult.NotSignedIn, RaResult.Unsupported -> emptyList()
        is RaResult.NotFound -> listOf(GameInfoRow("Achievements", "None listed", result.why))
        is RaResult.Failed ->
            if (offline(result.message)) emptyList() else listOf(GameInfoRow("Achievements", "Not loaded", result.message))
        is RaResult.Known -> {
            val progress = result.progress
            val how = "Recognised by ${result.matchedBy}" + if (result.stale) ". RetroAchievements could not be reached; this is the last progress loaded" else ""
            if (progress == null) {
                listOf(GameInfoRow("Achievements", "${result.game.numAchievements} to earn", "Your progress was not loaded. $how", url = RetroAchievementsClient.gameUrl(result.game.id), chip = "Achievements"))
            } else {
                listOf(
                    GameInfoRow(
                        "Achievements",
                        "${progress.earned} of ${progress.total}",
                        "${progress.pointsEarned} of ${progress.pointsTotal} points. $how",
                        screen = achievementsScreen(result.game.id, result.game.title, progress),
                        chip = "Achievements",
                    ),
                )
            }
        }
    }

/** The achievements as a page of rows: earned ones first, then the rest, and a way to the game on retroachievements.org. */
internal fun achievementsScreen(gameId: Int, gameTitle: String, progress: RaProgress): CatalogScreen = CatalogScreen(
    id = "retroachievements_$gameId",
    title = gameTitle,
    subtitle = "${progress.earned} of ${progress.total} earned, ${progress.pointsEarned} of ${progress.pointsTotal} points",
    groups = {
        val (earned, locked) = progress.achievements.partition { it.earned != null || it.earnedHardcore != null }
        listOfNotNull(
            CatalogGroup("ra_earned", "Earned (${earned.size})", earned.map { achievementItem(it) }).takeIf { earned.isNotEmpty() },
            CatalogGroup("ra_locked", "Not yet earned (${locked.size})", locked.map { achievementItem(it) }).takeIf { locked.isNotEmpty() },
            CatalogGroup(
                "ra_site",
                null,
                listOf(
                    ActionItem(
                        id = "ra_open_site",
                        title = "Open on retroachievements.org",
                        run = { context -> openAddress(context, RetroAchievementsClient.gameUrl(gameId)) },
                    ),
                ),
            ),
        )
    },
)

private fun achievementItem(achievement: RaAchievement): ActionItem {
    val hardcore = achievement.earnedHardcore
    val earned = achievement.earned
    return ActionItem(
        id = "ra_${achievement.id}",
        title = achievement.title,
        subtitle = "${achievement.description} (${achievement.points} points)",
        value = when {
            hardcore != null -> "Hardcore ${hardcore.take(10)}"
            earned != null -> earned.take(10)
            else -> null
        },
        run = {},
    )
}

/** Opens an address in the system browser (a link on a page, never a request droidtop makes). */
internal fun openAddress(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
