package dev.droidtop.library.computers

import android.content.Context
import android.content.Intent
import dev.droidtop.net.peer.Computer
import dev.droidtop.net.peer.Computers
import org.json.JSONObject

/**
 * Asking a paired computer to start one of its games (docs/SPEC.md 7o,
 * "Starting a game on a computer"; Droidtop/tracker#552), the one way for
 * every caller: the Steam module's "Stream with Steam Link" and opening a
 * computer's game from the library. The agent's request is `launch_game`
 * (droidtop-agent docs/DESIGN.md section 17); a Steam key is started by Steam
 * there, any other by the program hosting the agent (Pythia), which answers
 * `stream: windowcast` when windowcast shows the game's window.
 *
 * Every answer becomes one line in plain words with the next step.
 */
object ComputerLaunch {
    /** The `stream` of a `started` answer when windowcast shows the game. */
    const val STREAM_WINDOWCAST = "windowcast"

    /** windowcast's Android viewer. It takes no link yet, so it is opened plainly and the person picks the computer there. */
    const val WINDOWCAST_VIEWER = "dev.windowcast.viewer"

    /**
     * What one computer said, and the words for it. [definitive] is false when
     * only another computer might do better; [stream] is how the started game
     * is shown (null: nothing names a viewer, Steam Link for a Steam game).
     */
    data class Outcome(val started: Boolean, val line: String, val definitive: Boolean = true, val stream: String? = null)

    /** The computers to ask for a game, in order: the primary one first, then the one met most recently. */
    fun order(computers: List<Computer>): List<Computer> =
        computers.sortedWith(compareByDescending<Computer> { it.primary }.thenByDescending { it.lastSyncMs })

    /** The words for one agent reply to `launch_game` for the game [key]. */
    fun describe(reply: JSONObject, computer: String, game: String, handheld: String, key: String): Outcome {
        if (reply.has("unreachable")) {
            return Outcome(false, "$computer didn't answer (${reply.optString("unreachable")}). It has to be on and reachable, at home or through its WireGuard port.", definitive = false)
        }
        reply.optString("error").takeIf { reply.has("error") }?.let { return Outcome(false, "$computer: $it") }
        return when (reply.optString("result")) {
            "started" -> Outcome(true, "$game is starting on $computer.", stream = reply.optString("stream").takeIf { it.isNotBlank() })
            "agent_too_old" -> Outcome(false, "droidtop-agent on $computer is too old to start games. Update it on the computer.")
            "not_allowed" -> Outcome(
                false,
                "$computer hasn't allowed $handheld to start games. On $computer open droidtop-agent, Settings, Start games, and tick $handheld.",
            )
            "unknown_game" -> Outcome(
                false,
                if (key.startsWith("steam:")) {
                    "$computer doesn't have $game installed (its last scan didn't list it)."
                } else {
                    // An agent with no program hosting it (no launch_host) answers this for every key that is not Steam's.
                    "$computer can't start $game. Only a desktop manager such as Pythia starts games other than Steam's; droidtop-agent alone starts Steam games only."
                },
            )
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
    fun ask(candidates: List<Computer>, game: String, handheld: String, key: String, call: (Computer) -> JSONObject): Pair<Computer, Outcome>? {
        var last: Pair<Computer, Outcome>? = null
        for (computer in candidates) {
            val outcome = describe(call(computer), computer.name, game, handheld, key)
            last = computer to outcome
            if (outcome.definitive) return last
        }
        return last
    }

    /** Asks [candidates] in order to start the game [key]. Network work: never on the main thread. */
    fun start(context: Context, candidates: List<Computer>, key: String, title: String): Pair<Computer, Outcome>? =
        ask(candidates, title, Computers.deviceName(context), key) { pc ->
            Computers.call(context, pc, "launch_game", JSONObject().put("game", JSONObject().put("key", key).put("title", title)))
        }

    /**
     * Opens windowcast's viewer for a game a computer started with
     * `stream: windowcast`, and returns the line to show after [started].
     * The viewer is not published yet; when it is not here, this says so
     * rather than offering a store page.
     */
    fun openWindowcast(context: Context, computer: String, started: String): String {
        val open = context.packageManager.getLaunchIntentForPackage(WINDOWCAST_VIEWER)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return "$started windowcast's viewer isn't on this device to show it."
        val failure = runCatching { context.startActivity(open) }.exceptionOrNull()
            ?: return "$started In windowcast choose $computer."
        return "$started windowcast's viewer would not open: ${failure.message}."
    }
}
