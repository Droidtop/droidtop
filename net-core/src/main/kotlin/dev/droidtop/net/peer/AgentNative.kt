package dev.droidtop.net.peer

import org.json.JSONObject

/**
 * droidtop's side of droidtop-agent (docs/SPEC.md 7o "Computers"): the agent's
 * Rust core, built in the Droidtop/droidtop-agent repository as
 * `libdroidtop_agent.so` and fetched against `net-core/agent-lib.pin`. One
 * entry point: an operation name and its arguments as JSON, a JSON reply.
 * Pairing, the session channel, the save rule and the merges are the same
 * code the computer runs, so the two ends cannot drift apart.
 *
 * Every call blocks (it may wait on the network): callers run it on
 * Dispatchers.IO, never the main thread. Nothing in the library runs on its
 * own; there is no resident thread, listener or timer.
 *
 * A reply with `error` failed in words a screen can show; a reply with
 * `unreachable` means the computer did not answer on any path.
 */
object AgentNative {
    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("droidtop_agent") }.isSuccess }

    /** False in a build without the library (a local build that skipped the fetch). */
    val available: Boolean get() = loaded

    @JvmStatic
    private external fun nativeCall(op: String, args: String): String

    fun call(op: String, args: JSONObject = JSONObject()): JSONObject {
        if (!loaded) return JSONObject().put("error", "this build of droidtop has no computer sync library")
        return runCatching { JSONObject(nativeCall(op, args.toString())) }
            .getOrElse { JSONObject().put("error", it.message ?: it.javaClass.simpleName) }
    }

    /** The reply's failure, as the one line to show, or null when it succeeded. */
    fun failure(reply: JSONObject): String? = when {
        reply.has("error") -> reply.optString("error")
        reply.has("unreachable") -> reply.optString("unreachable")
        else -> null
    }
}
