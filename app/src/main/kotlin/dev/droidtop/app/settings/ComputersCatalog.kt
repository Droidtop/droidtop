package dev.droidtop.app.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.droidtop.app.LibraryCore
import dev.droidtop.app.PairComputerActivity
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.computers.ComputerLibrary
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.net.peer.AgentNative
import dev.droidtop.net.peer.Computer
import dev.droidtop.net.peer.Computers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date

/**
 * Settings > Accounts and sources > Computers (docs/SPEC.md 7o): the person's
 * computers running droidtop-agent. Pair one, see what the last sync with it
 * did, sync the library now, see the games it has, or forget it. Saves sync by
 * themselves around a game's launch and exit; plugin contexts sync from their
 * plugin. A catalog screen, so both Settings surfaces draw it.
 */
object ComputersCatalog {
    const val SCREEN_ID = "computers"

    /** How long "Sync now" waits for the library's first scan before it gives up. */
    private const val WAIT_FOR_LIBRARY_MS = 60_000L

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Computers",
        subtitle = "Your computers running droidtop-agent: saves, games and plugin data kept in step with them",
        groups = { context -> groups(context) },
    )

    /** The row that opens this screen, with how many computers are paired ([paired] is read off the main thread by the caller). */
    fun linkRow(paired: Int) = NestedScreenItem(
        id = "accounts_computers_link",
        title = "Computers",
        subtitle = "Pair a computer to keep saves, games and plugin data in step with it",
        registryId = SCREEN_ID,
        valueLabel = { if (paired == 0) "none paired" else "$paired paired" },
        icon = CatalogIcon.DESKTOP,
    )

    private suspend fun groups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val computers = Computers.list(context)
        buildList {
            add(
                CatalogGroup(
                    id = "computers_pair",
                    title = null,
                    items = buildList {
                        if (!AgentNative.available) {
                            add(ActionItem(id = "computers_missing", title = "This build of droidtop has no computer sync library", run = {}))
                        }
                        add(
                            ActionItem(
                                id = "computers_pair_one",
                                title = "Pair a computer",
                                subtitle = "Shows a code to type into droidtop-agent on the computer",
                                run = { ctx -> ctx.startActivity(PairComputerActivity.intent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                            ),
                        )
                        add(
                            ActionItem(
                                id = "computers_get_agent",
                                title = "Get droidtop-agent",
                                subtitle = "For Windows, Linux and macOS: the program the computer runs",
                                run = { ctx ->
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PairComputerActivity.AGENT_RELEASES)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                },
                            ),
                        )
                    },
                ),
            )
            if (computers.isNotEmpty()) {
                add(CatalogGroup(id = "computers_paired", title = "Paired", items = computers.map { computerRow(it) }))
            }
        }
    }

    private fun computerRow(computer: Computer) = NestedScreenItem(
        id = "computer_${computer.id.take(16)}",
        title = computer.name,
        subtitle = computer.lastLine ?: "Not synced yet",
        inline = computerScreen(computer.id),
        icon = CatalogIcon.DESKTOP,
    )

    private fun computerScreen(id: String) = CatalogScreen(
        id = "computer_$id",
        title = "Computer",
        groups = { context -> computerGroups(context, id) },
    )

    private suspend fun computerGroups(context: Context, id: String): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val computer = Computers.list(context).firstOrNull { it.id == id }
            ?: return@withContext listOf(CatalogGroup(id = "computer_gone", title = null, items = listOf(ActionItem(id = "computer_gone_row", title = "This computer is no longer paired", run = {}))))
        val games = ComputerLibrary.gamesOn(context, computer)
        val last = if (computer.lastSyncMs > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(computer.lastSyncMs)) else null
        listOf(
            CatalogGroup(
                id = "computer_state",
                title = computer.name,
                items = listOf(
                    ActionItem(
                        id = "computer_last",
                        title = "Last sync",
                        subtitle = computer.lastLine ?: "Saves sync when a game starts and ends; the library when you sync it here",
                        value = last ?: "never",
                        run = {},
                    ),
                    AsyncActionItem(
                        id = "computer_sync_library",
                        title = "Sync the library now",
                        subtitle = "This device's games go to ${computer.name}, and its games come here",
                        run = { ctx, onStatus ->
                            onStatus("Reading this device's games…")
                            val entries = libraryGames(ctx) ?: return@AsyncActionItem "The library is still being scanned; try again in a moment"
                            onStatus("Talking to ${computer.name}…")
                            withContext(Dispatchers.IO) { ComputerLibrary.sync(ctx, computer, entries) }
                        },
                    ),
                    ActionItem(
                        id = "computer_away",
                        title = "Away from home",
                        subtitle = if (computer.endpoints.isEmpty()) {
                            "${computer.name} gave no way in from outside its network. On the computer, forward UDP 47611 on the router and run droidtop-agent endpoint set <public address>:47611, or let a global IPv6 address through its firewall"
                        } else {
                            "Reached through WireGuard at ${computer.endpoints.joinToString(", ") { it.removePrefix("wg:") }} when it is not on this network"
                        },
                        run = {},
                    ),
                    NestedScreenItem(
                        id = "computer_games",
                        title = "Games on ${computer.name}",
                        subtitle = if (games.isEmpty()) "Sync the library to see them" else "What its last sync said it has installed",
                        inline = gamesScreen(computer, games),
                        valueLabel = { games.size.toString() },
                    ),
                ),
            ),
            CatalogGroup(
                id = "computer_forget",
                title = null,
                items = listOf(
                    ActionItem(
                        id = "computer_forget_row",
                        title = "Forget this computer",
                        subtitle = "Stops syncing with it; to sync again, pair it again",
                        confirmTitle = "Forget ${computer.name}?",
                        run = { ctx -> Thread { Computers.remove(ctx.applicationContext, computer.id) }.start() },
                    ),
                ),
            ),
        )
    }

    private fun gamesScreen(computer: Computer, games: List<ComputerLibrary.RemoteGame>) = CatalogScreen(
        id = "computer_games_${computer.id.take(16)}",
        title = "Games on ${computer.name}",
        groups = {
            listOf(
                CatalogGroup(
                    id = "computer_games_list",
                    title = null,
                    items = games.map { game ->
                        ActionItem(
                            id = "computer_game_${game.key}",
                            title = game.title,
                            subtitle = listOfNotNull(game.launcher, game.platform?.takeIf { it != "pc" }).joinToString(" · ").ifBlank { null },
                            value = game.sizeBytes.takeIf { it > 0 }?.let { "${it / (1024 * 1024)} MB" },
                            run = {},
                        )
                    },
                ),
            )
        },
    )

    private suspend fun libraryGames(context: Context): List<LibraryEntry>? {
        val library = LibraryCore.library(context)
        val state = library.backgroundScanState(LibraryKinds.GAMES)
        return state.value ?: run {
            library.scanInBackground(LibraryKinds.GAMES)
            withTimeoutOrNull(WAIT_FOR_LIBRARY_MS) { state.filterNotNull().first() }
        }
    }
}
