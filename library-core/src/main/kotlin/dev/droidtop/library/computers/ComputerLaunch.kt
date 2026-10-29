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

    /** windowcast's Android viewer (`dev.windowcast.viewer`), which opens a named host and window (windowcast docs/ANDROID-VIEWER.md). */
    const val WINDOWCAST_VIEWER = "dev.windowcast.viewer"

    /** The viewer's explicit intent: [VIEWER_CONNECT] with the host's device id, its address and, optionally, part of a window's title. */
    const val VIEWER_CONNECT = "dev.windowcast.viewer.action.CONNECT"
    const val EXTRA_HOST_ID = "dev.windowcast.viewer.extra.HOST_ID"
    const val EXTRA_ADDRESS = "dev.windowcast.viewer.extra.ADDRESS"
    const val EXTRA_WINDOW = "dev.windowcast.viewer.extra.WINDOW"

    /** The port a windowcast host listens on unless its owner changed it (windowcast `app/src/config.rs`, `listen`). */
    const val WINDOWCAST_DEFAULT_PORT = 47100

    private val HOST_PORT = Regex("""^(\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+):([0-9]{1,5})$""")

    /**
     * What one computer said, and the words for it. [definitive] is false when
     * only another computer might do better; [stream] is how the started game
     * is shown (null: nothing names a viewer, Steam Link for a Steam game).
     */
    data class Outcome(
        val started: Boolean,
        val line: String,
        val definitive: Boolean = true,
        val stream: String? = null,
        /** Where the host's windowcast listens, when the answer said (`HOST:PORT`); null when it did not. */
        val streamAddress: String? = null,
    )

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
            "started" -> Outcome(
                true,
                "$game is starting on $computer.",
                stream = reply.optString("stream").takeIf { it.isNotBlank() },
                streamAddress = reply.optString("address").takeIf { isHostPort(it) },
            )
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

    /** Whether [text] is `HOST:PORT` as the viewer takes it: an IPv6 host in brackets, a port from 1 to 65535, nothing else. */
    fun isHostPort(text: String): Boolean = HOST_PORT.matchEntire(text)?.groupValues?.get(2)?.toIntOrNull()?.let { it in 1..65535 } == true

    /**
     * Where to reach [computer]'s windowcast: the address its answer gave, else its most recent address with windowcast's
     * default port (the sync port in [Computer.addresses] is the agent's, not windowcast's). Null when it has no address.
     */
    fun windowcastAddress(computer: Computer, answered: String?): String? {
        answered?.takeIf { isHostPort(it) }?.let { return it }
        val host = computer.addresses.firstNotNullOfOrNull { it.substringBeforeLast(':', "").takeIf { h -> h.isNotEmpty() } } ?: return null
        val plain = if (host.startsWith("[") || !host.contains(':')) host else "[$host]"
        return "$plain:$WINDOWCAST_DEFAULT_PORT".takeIf { isHostPort(it) }
    }

    /** The extras of the viewer's request for [computer] and the game's [window] title, or null when the computer has no address to name. */
    fun viewerExtras(computer: Computer, answered: String?, window: String): Map<String, String>? {
        val address = windowcastAddress(computer, answered) ?: return null
        return buildMap {
            put(EXTRA_HOST_ID, computer.id)
            put(EXTRA_ADDRESS, address)
            window.trim().takeIf { it.isNotEmpty() }?.let { put(EXTRA_WINDOW, it) }
        }
    }

    /**
     * Opens windowcast's viewer on [computer] for a game it started with `stream: windowcast`, at the game's window, and
     * returns the line to show after [started]. The viewer connects only to a host it has paired with; if it has not, it
     * says so itself. With no address to name, the viewer is opened plainly and the person picks the computer there.
     * The viewer may not be here: this says so rather than offering a store page.
     */
    fun openWindowcast(context: Context, computer: Computer, answered: String?, title: String, started: String): String {
        val extras = viewerExtras(computer, answered, title)
        val open = if (extras == null) {
            context.packageManager.getLaunchIntentForPackage(WINDOWCAST_VIEWER)
        } else {
            Intent(VIEWER_CONNECT).setPackage(WINDOWCAST_VIEWER).also { intent -> extras.forEach { (k, v) -> intent.putExtra(k, v) } }
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return "$started windowcast's viewer isn't on this device to show it."
        val failure = runCatching { context.startActivity(open) }.exceptionOrNull()
            ?: return if (extras != null) "$started Opening ${computer.name} in windowcast." else "$started In windowcast choose ${computer.name}."
        return "$started windowcast's viewer would not open: ${failure.message}."
    }
}
