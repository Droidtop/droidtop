package dev.droidtop.stores.steam

import android.content.Context
import android.content.Intent
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.computers.ComputerLibrary
import dev.droidtop.library.stores.StorePlayOption
import dev.droidtop.net.peer.Computer
import dev.droidtop.net.peer.Computers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * "Stream with Steam Link" (docs/SPEC.md 7o, "Starting a game on a
 * computer"; Droidtop/tracker#552), one of a Steam game's ways to play. The
 * Steam module owns it because it owns how Steam games are handled.
 *
 * Steam Link takes no link that picks a computer or a game (rig run on the
 * Retroid Pocket 5, Steam Link 1.3.32), so this asks a paired computer's
 * agent to start the game and then opens Steam Link plainly; Steam Link
 * shows the computer it is paired with and its games. droidtop streams
 * nothing itself.
 *
 * Every way it can fail is named in plain words with the next step, and logged.
 */
object SteamLinkStream {
    const val OPTION_ID = "steam_link"
    const val PACKAGE = "com.valvesoftware.steamlink"
    private const val TITLE = "Stream with Steam Link"

    /** What one computer's agent said, and the words for it. [definitive] is false when only another computer might do better. */
    data class Outcome(val started: Boolean, val line: String, val definitive: Boolean = true)

    /** The computers to ask for a game, in order: the primary one first, then the one met most recently. */
    fun order(computers: List<Computer>): List<Computer> =
        computers.sortedWith(compareByDescending<Computer> { it.primary }.thenByDescending { it.lastSyncMs })

    /** The words for one agent reply to `launch_game`. */
    fun describe(reply: JSONObject, computer: String, game: String, handheld: String): Outcome {
        if (reply.has("unreachable")) {
            return Outcome(false, "$computer didn't answer (${reply.optString("unreachable")}). It has to be on and reachable, at home or through its WireGuard port.", definitive = false)
        }
        reply.optString("error").takeIf { reply.has("error") }?.let { return Outcome(false, "$computer: $it") }
        return when (reply.optString("result")) {
            "started" -> Outcome(true, "$game is starting on $computer.")
            "agent_too_old" -> Outcome(false, "droidtop-agent on $computer is too old to start games. Update it on the computer.")
            "not_allowed" -> Outcome(
                false,
                "$computer hasn't allowed $handheld to start games. On $computer open droidtop-agent, Settings, Start games, and tick $handheld.",
            )
            "unknown_game" -> Outcome(false, "$computer doesn't have $game installed (its last scan didn't list it).")
            "steam_not_running" -> Outcome(false, "Steam isn't running on $computer. Start Steam there and sign in, then try again.")
            "failed" -> Outcome(false, "$computer couldn't start $game: ${reply.optString("message").ifBlank { "no reason given" }}")
            else -> Outcome(false, "$computer answered in a way this version of droidtop doesn't know. Update droidtop and droidtop-agent.")
        }
    }

    /**
     * Asks each of [candidates] in turn until one gives an answer that is
     * about the game and the person rather than about the network; if none
     * does, the last unreachable one's words are the answer.
     */
    fun ask(candidates: List<Computer>, game: String, handheld: String, call: (Computer) -> JSONObject): Pair<Computer, Outcome>? {
        var last: Pair<Computer, Outcome>? = null
        for (computer in candidates) {
            val outcome = describe(call(computer), computer.name, game, handheld)
            last = computer to outcome
            if (outcome.definitive) return last
        }
        return last
    }

    /** The row on a Steam game's Play list: always the same title, with what pressing it will do. Reads files: never on the main thread. */
    fun option(context: Context, gameId: String): StorePlayOption {
        val computers = Computers.list(context)
        val having = ComputerLibrary.computersWith(context, "steam:$gameId", computers)
        val detail = when {
            computers.isEmpty() -> "No computer is paired yet. Pair one in Settings, Computers"
            having.isEmpty() -> "None of your paired computers has it installed"
            else -> "Starts it on ${order(having).first().name}, then opens Steam Link"
        }
        return StorePlayOption(OPTION_ID, TITLE, detail)
    }

    /** Whether Steam Link is on this device. */
    fun installed(context: Context): Boolean = context.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    /** Does the whole thing and returns the one line to show. Network work: it switches to IO itself. */
    suspend fun stream(context: Context, gameId: String, title: String): String = withContext(Dispatchers.IO) {
        if (!installed(context)) {
            // The store page is the offer; droidtop installs nothing itself.
            val opened = PcRunnerOptions.openStorePage(context, PACKAGE)
            return@withContext "Steam Link isn't installed on this device. " + (opened ?: "Its store page is open: install it, pair it with your computer once, then try again.")
        }
        val computers = Computers.list(context)
        if (computers.isEmpty()) {
            return@withContext "No computer is paired with this device. Pair the computer that has $title in Settings, Computers, Pair a computer."
        }
        val having = order(ComputerLibrary.computersWith(context, "steam:$gameId", computers))
        if (having.isEmpty()) {
            return@withContext "None of your paired computers has $title installed. Install it there, then sync the library from Settings, Computers."
        }
        val handheld = Computers.deviceName(context)
        val (computer, outcome) = ask(having, title, handheld) { pc ->
            Computers.call(
                context,
                pc,
                "launch_game",
                JSONObject().put("game", JSONObject().put("key", "steam:$gameId").put("title", title)),
            )
        } ?: return@withContext "No computer could be asked."
        if (!outcome.started) {
            Timber.w("Stream with Steam Link: %s (steam:%s): %s", computer.name, gameId, outcome.line)
            return@withContext outcome.line
        }
        val open = context.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val failure = runCatching { context.startActivity(open) }.exceptionOrNull()
        if (open == null || failure != null) {
            Timber.w(failure, "Stream with Steam Link: Steam Link did not open")
            return@withContext "${outcome.line} Steam Link would not open: ${failure?.message ?: "it has no launcher"}."
        }
        "${outcome.line} In Steam Link choose ${computer.name} and Start Playing."
    }
}
