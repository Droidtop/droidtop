package dev.droidtop.shell.gamepad

import android.content.Context
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
import dev.droidtop.library.displayName
import dev.droidtop.library.achievements.RaAchievement
import dev.droidtop.library.achievements.RaProgress
import dev.droidtop.library.achievements.RaResult
import dev.droidtop.library.achievements.RetroAchievements
import dev.droidtop.library.achievements.RetroAchievementsClient
import dev.droidtop.library.gameinfo.CompatResult
import dev.droidtop.library.gameinfo.EmulatorCompat
import dev.droidtop.library.gameinfo.HowLongToBeat
import dev.droidtop.library.gameinfo.HltbResult
import dev.droidtop.library.saves.SaveCopies
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
 * runs it (docs/SPEC.md 7h, "Game info"). Every game's page draws it as one of its rows (`PageFact`, the page's
 * frame, Droidtop/tracker#845); the saves and the achievements sign-in are rows of its Options.
 */
internal data class GameInfoRow(
    val title: String,
    val value: String? = null,
    val subtitle: String? = null,
    /** A page of rows that A opens (the achievements). */
    val screen: CatalogScreen? = null,
    /** An address that A opens in the browser. */
    val url: String? = null,
    /** A step A takes in place (the guided key setup of the sign-in row); before [screen] and [url]. */
    val run: ((Context) -> Unit)? = null,
    /** Which source said it, so a page places the row by what it is and never by its English title. */
    val kind: GameInfoKind? = null,
)

/** The sources of [GameInfoRow]s ([rememberGameInfoRows]). */
internal enum class GameInfoKind { SAVES, ACHIEVEMENTS, PLAY_TIME, COMPATIBILITY }

/** Whether this is the row of a game RetroAchievements knows while no key is stored ([ACHIEVEMENTS_SIGN_IN]). */
internal val GameInfoRow.isAchievementsSignIn: Boolean
    get() = kind == GameInfoKind.ACHIEVEMENTS && subtitle == ACHIEVEMENTS_SIGN_IN

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
internal fun rememberGameInfoRows(
    entry: LibraryEntry,
    enabled: Boolean = true,
    // The game's other copies (docs/SPEC.md 7o, "Saves between copies"): the Saves screen offers their saves.
    copies: List<LibraryEntry> = emptyList(),
): List<GameInfoRow> {
    val context = LocalContext.current.applicationContext
    // Back from the guided key setup with a key stored: the rows are asked again, so the sign-in row becomes the
    // achievements. Checked only while that row shows; the key store is read off the main thread.
    var signedIn by remember(entry.id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val copyIds = copies.map { it.id }
    val rows by produceState(emptyList<GameInfoRow>(), entry.id, enabled, signedIn, copyIds) {
        if (!enabled) return@produceState
        var achievements = emptyList<GameInfoRow>()
        var playTime = emptyList<GameInfoRow>()
        var compatibility = emptyList<GameInfoRow>()
        var saves = emptyList<GameInfoRow>()
        coroutineScope {
            launch {
                achievements = withContext(Dispatchers.IO) { achievementRows(context, entry) }.map { it.copy(kind = GameInfoKind.ACHIEVEMENTS) }
                value = saves + achievements + playTime + compatibility
            }
            launch {
                playTime = withContext(Dispatchers.IO) { playTimeRows(context, entry) }.map { it.copy(kind = GameInfoKind.PLAY_TIME) }
                value = saves + achievements + playTime + compatibility
            }
            launch {
                compatibility = withContext(Dispatchers.IO) { compatibilityRows(context, entry) }.map { it.copy(kind = GameInfoKind.COMPATIBILITY) }
                value = saves + achievements + playTime + compatibility
            }
            launch {
                saves = saveRows(context, entry, copies).map { it.copy(kind = GameInfoKind.SAVES) }
                value = saves + achievements + playTime + compatibility
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (rows.any { it.isAchievementsSignIn }) {
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
internal suspend fun saveRows(context: Context, entry: LibraryEntry, copies: List<LibraryEntry> = emptyList()): List<GameInfoRow> {
    val report = SaveDetector.report(context, entry) ?: return emptyList()
    val now = System.currentTimeMillis()
    val volumes = dev.droidtop.library.saves.StorageVolumeNames.of(context)
    val others = copies.filter { it.id != entry.id }.mapNotNull { copy ->
        SaveCopies.sideOf(copy.id, SaveDetector.report(context, copy)?.status)?.let { copy to it }
    }
    val copying = if (copies.any { it.id != entry.id }) {
        val here = SaveCopies.sideOf(entry.id, report.status)
        // What each copy would replace, asked of the disk here, off the main thread, not when the screen draws.
        val replaced = withContext(Dispatchers.IO) { others.map { (_, side) -> here?.let { SaveCopies.plan(side, it).replaced.size } ?: 0 } }
        SaveCopying(here, others.zip(replaced) { (copy, side), count -> SaveCopying.Other(copy, side, count) }, SaveCopies.backups(context, entry.id))
    } else {
        null
    }
    return listOf(
        GameInfoRow(
            title = "Saves",
            value = SaveLines.value(report.status, now),
            subtitle = SaveLines.subtitle(report, now, volumes),
            screen = savesScreen(entry, report, now, volumes, copying),
        ),
    )
}

private fun saveFiles(count: Int) = if (count == 1) "1 save file" else "$count save files"

/** What the Saves screen offers between a game's copies: this copy's saves, the other copies' and this copy's backups. */
internal class SaveCopying(
    val here: SaveCopies.Side?,
    val others: List<Other>,
    val backups: List<SaveCopies.Backup>,
) {
    /** Another copy with saves, and how many of this copy's save files copying them would replace. */
    class Other(val copy: LibraryEntry, val side: SaveCopies.Side, val replaced: Int)
}

/**
 * "Saves between copies" (docs/SPEC.md 7o, Droidtop/tracker#745): copy another copy's saves to this one, asked first
 * with what is replaced, this copy's saves backed up; and put a backup back. The other copy's saves are only read.
 */
private fun copyGroups(entry: LibraryEntry, copying: SaveCopying, nowMs: Long, volumes: Map<String, String>): List<CatalogGroup> {
    val here = copying.here
    val rows = if (here == null) {
        listOf(
            ActionItem(
                id = "saves_copy_none",
                title = "No saves found for this copy yet",
                subtitle = "Play it and save once, so droidtop learns where its saves go; then copy saves here",
            ),
        )
    } else {
        copying.others.mapIndexed { index, other ->
            val copy = other.copy
            val side = other.side
            val replaced = other.replaced
            val from = dev.droidtop.library.originLabel(copy, 2).full
            val engine = SaveCopies.sameEngine(copy, entry)
            AsyncActionItem(
                id = "saves_copy_$index",
                title = "Copy saves from $from",
                subtitle = "${saveFiles(side.files.size)}, newest ${SaveLines.ago(side.newestMs, nowMs)}, in ${SaveLines.folder(side.folder.path, volumes, short = true)}",
                confirmTitle = listOfNotNull(
                    if (replaced > 0) "Replace ${saveFiles(replaced)} of this copy's? They are backed up first" else "Add these saves to this copy? Its own are backed up first",
                    if (engine != null) "Both copies run on ${engine.displayName()}." else "droidtop can't tell that these saves fit this copy.",
                ).joinToString(" "),
                run = { ctx, _ ->
                    val copied = SaveCopies.copy(ctx, side, here)
                    "Copied ${saveFiles(copied)} from $from. This copy's old saves are backed up"
                },
            )
        }.ifEmpty {
            listOf(ActionItem(id = "saves_copy_others_none", title = "No other copy has saves yet"))
        }
    }
    val restore = copying.backups.mapIndexed { index, backup ->
        AsyncActionItem(
            id = "saves_restore_$index",
            title = "Put back the saves from ${SaveLines.ago(backup.takenMs, nowMs)}",
            subtitle = "${saveFiles(backup.files)} backed up before saves were copied here",
            confirmTitle = "Put this copy's saves back as they were? Saves copied in since are removed; saves made since are kept",
            run = { _, _ ->
                val restored = SaveCopies.restore(backup)
                "Put back ${saveFiles(restored)}"
            },
        )
    }
    return listOfNotNull(
        CatalogGroup("saves_copies", "Saves from this game's other copies", rows),
        CatalogGroup("saves_backups", "Backups", restore).takeIf { restore.isNotEmpty() },
    )
}

/** The details of a game's saves, and the two ways the person corrects droidtop. */
internal fun savesScreen(
    entry: LibraryEntry,
    report: SaveReport,
    nowMs: Long,
    volumes: Map<String, String> = emptyMap(),
    copying: SaveCopying? = null,
): CatalogScreen = CatalogScreen(
    id = "saves_${entry.id}",
    title = GameNaming.displayName(entry.title),
    subtitle = SaveLines.value(report.status, nowMs),
    groups = {
        val facts = SaveLines.details(report, nowMs, volumes).mapIndexed { index, (name, value) ->
            ActionItem(id = "saves_fact_$index", title = name, subtitle = value)
        }
        val turnedOff = report.status is SaveStatus.TurnedOff
        listOfNotNull(
            CatalogGroup("saves_about", null, listOf(ActionItem(id = "saves_summary", title = SaveLines.value(report.status, nowMs), subtitle = SaveLines.subtitle(report, nowMs, volumes)))),
            CatalogGroup("saves_facts", "Where droidtop looked", facts).takeIf { facts.isNotEmpty() },
        ) + copying?.let { copyGroups(entry, it, nowMs, volumes) }.orEmpty() + listOfNotNull(
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

internal suspend fun achievementRows(context: Context, entry: LibraryEntry): List<GameInfoRow> {
    // A console game: RetroAchievements. A store's game: the store's own (Steam, Droidtop/tracker#230), in the same row.
    val retro = achievementRowsFor(RetroAchievements.lookup(context, entry), entry.id)
    if (retro.isNotEmpty()) return retro
    val key = entry.pcInfo?.storeId
    val store = key?.let { dev.droidtop.library.stores.StoreLibraries.forKey(it) }
    if (key != null && store != null) {
        val rows = storeAchievementRowsFor(store.label, key.substringAfter(':'), store.achievements(context, key.substringAfter(':')))
        if (rows.isNotEmpty()) return rows
    }
    // Neither: a plugin that provides achievements (docs/plugin-api.md 3 A8, Droidtop/tracker#143), in the same row.
    val (label, list) = dev.droidtop.library.integrations.PluginAchievements.read(context, entry) ?: return emptyList()
    return storeAchievementRowsFor(label, entry.id.hashCode().toUInt().toString(), Result.success(list))
}

/**
 * The Achievements row of a store's game (Droidtop/tracker#230): earned of all, opening the same page of rows
 * RetroAchievements' row opens. None when the store reports none, nobody is signed in, or the device is offline; a
 * game with no achievements has no row. Pure, for the tests.
 */
internal fun storeAchievementRowsFor(storeLabel: String, gameId: String, result: Result<dev.droidtop.library.stores.StoreAchievements>?): List<GameInfoRow> {
    result ?: return emptyList()
    val list = result.getOrElse { failure ->
        val message = failure.message ?: "$storeLabel did not answer"
        return if (offline(message) || message.contains("could not be reached")) emptyList()
        else listOf(GameInfoRow("Achievements", "Not loaded", message))
    }
    if (list.total == 0) return emptyList()
    return listOf(
        GameInfoRow(
            "Achievements",
            "${list.earned} of ${list.total}",
            "From $storeLabel, as your account has them",
            screen = storeAchievementsScreen(storeLabel, gameId, list),
        ),
    )
}

/** A store game's achievements as a page of rows, laid out as RetroAchievements' are: earned first, then the rest. */
internal fun storeAchievementsScreen(storeLabel: String, gameId: String, list: dev.droidtop.library.stores.StoreAchievements): CatalogScreen = CatalogScreen(
    id = "store_achievements_${storeLabel.lowercase()}_$gameId",
    title = "Achievements",
    subtitle = "${list.earned} of ${list.total} earned on $storeLabel",
    groups = {
        val (earned, locked) = list.achievements.partition { it.earnedAtMs != null }
        listOfNotNull(
            CatalogGroup("store_earned", "Earned (${earned.size})", earned.sortedByDescending { it.earnedAtMs }.map(::storeAchievementItem)).takeIf { earned.isNotEmpty() },
            CatalogGroup("store_locked", "Not yet earned (${locked.size})", locked.map(::storeAchievementItem)).takeIf { locked.isNotEmpty() },
            list.pageUrl?.let { url ->
                CatalogGroup(
                    "store_site",
                    null,
                    listOf(ActionItem(id = "store_open_site", title = "Open on $storeLabel", run = { context -> dev.droidtop.library.Links.open(context, url) })),
                )
            },
        )
    },
)

private val EARNED_DATE = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)

internal fun storeAchievementItem(achievement: dev.droidtop.library.stores.StoreAchievement): ActionItem {
    val earned = achievement.earnedAtMs
    val secret = achievement.hidden && earned == null
    val progress = achievement.progress
    val max = achievement.progressMax
    return ActionItem(
        id = "store_${achievement.id}",
        title = if (secret) "Hidden achievement" else achievement.title,
        subtitle = if (secret) "Described once you earn it" else achievement.description.ifBlank { null },
        value = when {
            earned != null -> synchronized(EARNED_DATE) { EARNED_DATE.format(java.util.Date(earned)) }
            progress != null && max != null -> "${progress.toInt()} of ${max.toInt()}"
            else -> null
        },
    )
}

/** The rows for what RetroAchievements said about a game ([entryId] answers a proposal). Pure, for the tests. */
internal fun achievementRowsFor(result: RaResult, entryId: String = ""): List<GameInfoRow> =
    when (result) {
        // A title match is asked, never shown by itself (Droidtop/tracker#318 decision 7a): wrong achievements and wrong
        // progress are worse than none.
        is RaResult.Proposed -> listOf(
            GameInfoRow(
                "Achievements",
                "Is it ${result.game.title}?",
                "RetroAchievements has a game of this name, but no file of yours is on its list. Confirm it to see its achievements",
                screen = raProposalScreen(entryId, result.game),
            ),
        )
        RaResult.Unsupported -> emptyList()
        // A console RetroAchievements covers, and no key yet: the feature says it is there (Droidtop/tracker#143).
        RaResult.NotSignedIn -> listOf(
            GameInfoRow(
                "Achievements",
                "Sign in",
                ACHIEVEMENTS_SIGN_IN,
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
                listOf(GameInfoRow("Achievements", "${result.game.numAchievements} to earn", "Your progress was not loaded. $how", url = RetroAchievementsClient.gameUrl(result.game.id)))
            } else {
                listOf(
                    GameInfoRow(
                        "Achievements",
                        "${progress.earned} of ${progress.total}",
                        "${progress.pointsEarned} of ${progress.pointsTotal} points. $how",
                        screen = achievementsScreen(result.game.id, result.game.title, progress),
                    ),
                )
            }
        }
    }

/** The two answers to a RetroAchievements game proposed by title ([RaResult.Proposed]). */
internal fun raProposalScreen(entryId: String, game: dev.droidtop.library.achievements.RaGame): CatalogScreen = CatalogScreen(
    id = "ra_proposal_${game.id}",
    title = game.title,
    subtitle = "${game.numAchievements} achievements, ${game.points} points",
    groups = {
        listOf(
            CatalogGroup(
                "ra_proposal",
                "Is this game ${game.title} on RetroAchievements?",
                listOf(
                    AsyncActionItem(
                        id = "ra_confirm",
                        title = "Yes, show its achievements",
                        subtitle = "Your progress in it shows on this game from now on",
                        run = { ctx, _ ->
                            dev.droidtop.library.achievements.RaChoices.confirm(ctx, entryId, game.id)
                            // What the person confirmed is who the game is (#318 §2): a claim later devices can use.
                            dev.droidtop.library.identity.LibraryIdentities.claim(
                                ctx, entryId, null, listOf(dev.droidtop.library.identity.Claim(dev.droidtop.library.identity.ClaimNamespaces.RA, game.id.toString())), "manual",
                            )
                            "Achievements for ${game.title} show on this game"
                        },
                    ),
                    AsyncActionItem(
                        id = "ra_not_this",
                        title = "Not this game",
                        subtitle = "droidtop does not ask about it again",
                        run = { ctx, _ ->
                            dev.droidtop.library.achievements.RaChoices.notThis(ctx, entryId, game.id)
                            "droidtop will not show ${game.title}'s achievements on this game"
                        },
                    ),
                ),
            ),
        )
    },
)

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
                        run = { context -> dev.droidtop.library.Links.open(context, RetroAchievementsClient.gameUrl(gameId)) },
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
    )
}

