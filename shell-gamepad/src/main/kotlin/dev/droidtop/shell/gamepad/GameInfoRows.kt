package dev.droidtop.shell.gamepad

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.achievements.RaAchievement
import dev.droidtop.library.achievements.RaProgress
import dev.droidtop.library.achievements.RaResult
import dev.droidtop.library.achievements.RetroAchievements
import dev.droidtop.library.achievements.RetroAchievementsClient
import dev.droidtop.library.gameinfo.CompatResult
import dev.droidtop.library.gameinfo.EmulatorCompat
import dev.droidtop.library.gameinfo.HowLongToBeat
import dev.droidtop.library.gameinfo.HltbResult
import dev.droidtop.library.saves.SaveDetector
import dev.droidtop.library.saves.SaveLines
import dev.droidtop.library.saves.SaveReport
import dev.droidtop.library.saves.SaveStatus
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One fact a game's page gets from outside the library: its achievements, how long it takes, how an emulator
 * runs it (docs/SPEC.md 7h, "Game info"). The PC game page draws it as one of its rows (`PageFact`), the plain
 * detail screen of a console game as a line and, when there is something to open, a chip.
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
    /** A step A or the chip takes in place (the guided key setup of the sign-in row); before [screen] and [url]. */
    val run: ((Context) -> Unit)? = null,
)

/** The row a game RetroAchievements knows gets while no key is stored: A opens the guided key setup in place. */
internal const val ACHIEVEMENTS_SIGN_IN = "Show your achievements: sign in to RetroAchievements"

/**
 * The rows for [entry], each filled in off the main thread as its source answers: RetroAchievements (a console
 * game, once the person is signed in), HowLongToBeat and an emulator's compatibility list. The sources are asked
 * when the page opens, never while a list draws, and what they learn is cached (RetroAchievements, HowLongToBeat
 * and the lists each keep their own). A fact nothing knows is not a row; a failure is one, except being offline,
 * which on a handheld is ordinary and says nothing. [enabled] is false for something that is not a game.
 */
@Composable
internal fun rememberGameInfoRows(entry: LibraryEntry, enabled: Boolean = true): List<GameInfoRow> {
    val context = LocalContext.current.applicationContext
    // Back from the guided key setup with a key stored: the rows are asked again, so the sign-in row becomes the
    // achievements. Checked only while that row shows; the key store is read off the main thread.
    var signedIn by remember(entry.id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val rows by produceState(emptyList<GameInfoRow>(), entry.id, enabled, signedIn) {
        if (!enabled) return@produceState
        var achievements = emptyList<GameInfoRow>()
        var playTime = emptyList<GameInfoRow>()
        var compatibility = emptyList<GameInfoRow>()
        var saves = emptyList<GameInfoRow>()
        coroutineScope {
            launch {
                achievements = withContext(Dispatchers.IO) { achievementRows(context, entry) }
                value = saves + achievements + playTime + compatibility
            }
            launch {
                playTime = withContext(Dispatchers.IO) { playTimeRows(context, entry) }
                value = saves + achievements + playTime + compatibility
            }
            launch {
                compatibility = withContext(Dispatchers.IO) { compatibilityRows(context, entry) }
                value = saves + achievements + playTime + compatibility
            }
            launch {
                saves = saveRows(context, entry)
                value = saves + achievements + playTime + compatibility
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (rows.any { it.subtitle == ACHIEVEMENTS_SIGN_IN }) {
            scope.launch { if (withContext(Dispatchers.IO) { RetroAchievements.isConfigured(context) }) signedIn++ }
        }
    }
    return rows
}

/**
 * What droidtop knows of this game's saves: found, none found, no rule, or a rule it cannot apply to this game yet
 * (docs/SPEC.md 7o "Saves"). Worked out off the main thread when the page opens, never while a list draws. A thing
 * with no saves to speak of (an app) has no row.
 */
internal suspend fun saveRows(context: Context, entry: LibraryEntry): List<GameInfoRow> {
    val report = SaveDetector.report(context, entry) ?: return emptyList()
    val now = System.currentTimeMillis()
    return listOf(
        GameInfoRow(
            title = "Saves",
            value = SaveLines.value(report.status, now),
            subtitle = SaveLines.subtitle(report, now),
            screen = savesScreen(entry, report, now),
            chip = "Saves",
        ),
    )
}

/** The details of a game's saves, and the two ways the person corrects droidtop. */
internal fun savesScreen(entry: LibraryEntry, report: SaveReport, nowMs: Long): CatalogScreen = CatalogScreen(
    id = "saves_${entry.id}",
    title = GameNaming.displayName(entry.title),
    subtitle = SaveLines.value(report.status, nowMs),
    groups = {
        val facts = SaveLines.details(report, nowMs).mapIndexed { index, (name, value) ->
            ActionItem(id = "saves_fact_$index", title = name, subtitle = value, run = {})
        }
        val turnedOff = report.status is SaveStatus.TurnedOff
        listOfNotNull(
            CatalogGroup("saves_about", null, listOf(ActionItem(id = "saves_summary", title = SaveLines.value(report.status, nowMs), subtitle = SaveLines.subtitle(report, nowMs), run = {}))),
            CatalogGroup("saves_facts", "Where droidtop looked", facts).takeIf { facts.isNotEmpty() },
            CatalogGroup(
                "saves_fix",
                "Not right?",
                listOf(
                    if (turnedOff) {
                        AsyncActionItem(
                            id = "saves_again",
                            title = "Look for this game's saves again",
                            subtitle = "droidtop goes back to working out where they are",
                            run = { ctx, _ ->
                                SaveDetector.matchAgain(ctx, entry.id)
                                "droidtop will look for this game's saves again"
                            },
                        )
                    } else {
                        AsyncActionItem(
                            id = "saves_off",
                            title = "Don't look for this game's saves",
                            subtitle = "For a game whose rule is wrong. Nothing is deleted",
                            run = { ctx, _ ->
                                SaveDetector.turnOff(ctx, entry.id)
                                "droidtop will not look for this game's saves"
                            },
                        )
                    },
                ),
            ),
        )
    },
)

private fun offline(message: String) = message.startsWith("No connection")

internal fun achievementRows(context: Context, entry: LibraryEntry): List<GameInfoRow> =
    achievementRowsFor(RetroAchievements.lookup(context, entry))

/** The rows for what RetroAchievements said about a game. Pure, for the tests. */
internal fun achievementRowsFor(result: RaResult): List<GameInfoRow> =
    when (result) {
        RaResult.Unsupported -> emptyList()
        // A console RetroAchievements covers, and no key yet: the feature says it is there (Droidtop/tracker#143).
        RaResult.NotSignedIn -> listOf(
            GameInfoRow(
                "Achievements",
                "Sign in",
                ACHIEVEMENTS_SIGN_IN,
                chip = "Sign in for achievements",
                run = { ctx -> dev.droidtop.library.scraper.ScraperKeySetupLink.open(ctx, dev.droidtop.library.scraper.ScraperKeyService.RETROACHIEVEMENTS) },
            ),
        )
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

internal fun playTimeRows(context: Context, entry: LibraryEntry): List<GameInfoRow> {
    val title = entry.gameName ?: GameNaming.displayName(entry.title)
    return when (val result = HowLongToBeat.lookup(context, title)) {
        HltbResult.Off, HltbResult.NotFound -> emptyList()
        is HltbResult.Failed -> if (offline(result.message)) emptyList() else listOf(GameInfoRow("How long to beat", "Not loaded", result.message))
        is HltbResult.Found -> {
            val summary = HowLongToBeat.summary(result.game)
            if (summary.isEmpty()) {
                emptyList()
            } else {
                val close = if (result.exact) "" else "Closest match: ${result.game.name}. "
                listOf(
                    GameInfoRow(
                        "How long to beat",
                        summary,
                        close + "From HowLongToBeat players, a guide and not a promise.",
                        url = result.game.pageUrl,
                        chip = "HowLongToBeat",
                    ),
                )
            }
        }
    }
}

internal fun compatibilityRows(context: Context, entry: LibraryEntry): List<GameInfoRow> =
    when (val result = EmulatorCompat.lookup(context, entry)) {
        CompatResult.Off, CompatResult.Unsupported, is CompatResult.NotListed -> emptyList()
        is CompatResult.Failed -> if (offline(result.message)) emptyList() else listOf(GameInfoRow("Emulator compatibility", "Not loaded", result.message))
        is CompatResult.Found -> listOf(
            GameInfoRow(
                "Emulator compatibility",
                result.info.rating,
                "From ${result.info.emulator}'s own list: other people's results on other hardware, not a verdict.",
                url = result.info.listUrl,
            ),
        )
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
