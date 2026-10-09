package dev.droidtop.pluginhost

import android.content.Context
import dev.droidtop.net.peer.Computers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Plugin context sync, droidtop's side (docs/plugin-api.md 3 F8, docs/SPEC.md
 * 7o "Computers"). A context is a plugin's supporting state, kept in step both
 * ways with a program on the person's paired computers: F95Checker's watch
 * list for the F95 plugin is the first. The plugin declares the context's
 * fields, which way each travels and who wins when both sides changed it;
 * droidtop keeps the records, tracks changes against a baseline per computer,
 * and merges with the computer through droidtop-agent (the merge itself is the
 * agent core's, the same code the computer runs).
 *
 * Kept in `plugins/<id>/contexts/`, outside the plugin's own data folder, so
 * the plugin changes records only through `context.put` and `context.remove`
 * and every change is seen by the next sync.
 */
class PluginContexts(private val appContext: Context) {

    /** A context's state on this device: the plugin's declaration, its records and the fields waiting for the person. */
    data class State(val decl: JSONObject, val records: JSONObject, val conflicts: JSONArray)

    private fun dir(pluginId: String): File = File(PluginStore.root(appContext), "$pluginId/contexts").apply { mkdirs() }

    private fun stateFile(pluginId: String, contextId: String) = File(dir(pluginId), "$contextId.json")

    private fun baselineFile(pluginId: String, contextId: String, computerId: String) = File(dir(pluginId), "$contextId.$computerId.baseline.json")

    @Synchronized
    fun load(pluginId: String, contextId: String): State? {
        val file = stateFile(pluginId, contextId)
        if (!file.isFile) return null
        return runCatching {
            val o = JSONObject(file.readText())
            State(o.getJSONObject("decl"), o.optJSONObject("records") ?: JSONObject(), o.optJSONArray("conflicts") ?: JSONArray())
        }.getOrNull()
    }

    @Synchronized
    private fun save(pluginId: String, contextId: String, state: State) {
        val file = stateFile(pluginId, contextId)
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(JSONObject().put("decl", state.decl).put("records", state.records).put("conflicts", state.conflicts).toString())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw BrokerException(PluginErrorCode.FAILED, "the context could not be saved")
        }
    }

    /** Declares (or re-declares) a context; its records are kept. */
    @Synchronized
    fun open(pluginId: String, contextId: String, decl: JSONObject): State {
        val state = load(pluginId, contextId)?.copy(decl = decl) ?: State(decl, JSONObject(), JSONArray())
        save(pluginId, contextId, state)
        return state
    }

    private fun opened(pluginId: String, contextId: String): State =
        load(pluginId, contextId) ?: throw BrokerException(PluginErrorCode.INVALID_ARGS, "open the $contextId context first")

    /** Sets fields of one record, adding it when it is not there. */
    @Synchronized
    fun put(pluginId: String, contextId: String, key: String, fields: JSONObject) {
        val state = opened(pluginId, contextId)
        val record = state.records.optJSONObject(key) ?: JSONObject()
        fields.keys().forEach { record.put(it, fields.get(it)) }
        state.records.put(key, record)
        save(pluginId, contextId, state)
    }

    @Synchronized
    fun remove(pluginId: String, contextId: String, key: String): Boolean {
        val state = opened(pluginId, contextId)
        val removed = state.records.remove(key) != null
        if (removed) save(pluginId, contextId, state)
        return removed
    }

    /**
     * Merges the context with every paired computer, one after the other.
     * Blocks on the network: the broker runs it on a binder thread, never the
     * main thread. Returns one line per computer and the conflicts left.
     */
    @Synchronized
    fun sync(pluginId: String, contextId: String): JSONObject {
        var state = opened(pluginId, contextId)
        val lines = JSONArray()
        for (computer in Computers.list(appContext)) {
            val baselineFile = baselineFile(pluginId, contextId, computer.id)
            val baseline = runCatching { JSONObject(baselineFile.readText()) }.getOrDefault(JSONObject())
            val reply = Computers.call(
                appContext,
                computer,
                "sync_context",
                JSONObject().put("decl", state.decl).put("device", state.records).put("baseline", baseline),
            )
            val failure = reply.optString("error").ifBlank { reply.optString("unreachable") }
            if (failure.isNotBlank()) {
                lines.put("${computer.name}: $failure")
                continue
            }
            val conflicts = JSONArray()
            for (i in 0 until state.conflicts.length()) {
                val c = state.conflicts.optJSONObject(i) ?: continue
                if (c.optString("computer_id") != computer.id) conflicts.put(c)
            }
            val theirs = reply.optJSONArray("conflicts") ?: JSONArray()
            for (i in 0 until theirs.length()) {
                theirs.optJSONObject(i)?.let { conflicts.put(it.put("computer_id", computer.id).put("computer_name", computer.name)) }
            }
            state = State(state.decl, reply.optJSONObject("device") ?: state.records, conflicts)
            save(pluginId, contextId, state)
            baselineFile.writeText((reply.optJSONObject("baseline") ?: JSONObject()).toString())
            val line = when {
                reply.optBoolean("deferred") -> "${computer.name}: ${reply.optString("message").ifBlank { "changes wait until the app there is closed" }}"
                else -> "${computer.name}: in step (${reply.optInt("sent")} changes sent, ${theirs.length()} to settle)"
            }
            lines.put(line)
            Computers.noteSync(appContext, computer.id, line)
        }
        if (lines.length() == 0) lines.put("No computer is paired: pair one under Settings > Accounts and sources > Computers")
        return JSONObject().put("lines", lines).put("conflicts", state.conflicts.length())
    }

    /**
     * Settles a field both sides changed: [keep] is `device` or `computer`.
     * Keeping the computer's value takes it here; keeping this device's moves
     * the baseline to the computer's value, so the next sync sends this one.
     */
    @Synchronized
    fun resolve(pluginId: String, contextId: String, key: String, field: String, keep: String): Boolean {
        val state = opened(pluginId, contextId)
        var settled: JSONObject? = null
        val left = JSONArray()
        for (i in 0 until state.conflicts.length()) {
            val c = state.conflicts.optJSONObject(i) ?: continue
            if (settled == null && c.optString("key") == key && c.optString("field") == field) settled = c else left.put(c)
        }
        val conflict = settled ?: return false
        val computerValue = if (conflict.isNull("computer")) JSONObject.NULL else conflict.opt("computer")
        if (keep == "computer") {
            val record = state.records.optJSONObject(key) ?: JSONObject().also { state.records.put(key, it) }
            record.put(field, computerValue)
        }
        val baselineFile = baselineFile(pluginId, contextId, conflict.optString("computer_id"))
        val baseline = runCatching { JSONObject(baselineFile.readText()) }.getOrDefault(JSONObject())
        val baseRecord = baseline.optJSONObject(key) ?: JSONObject().also { baseline.put(key, it) }
        baseRecord.put(field, computerValue)
        baselineFile.writeText(baseline.toString())
        save(pluginId, contextId, State(state.decl, state.records, left))
        return true
    }

    companion object {
        /** The contexts droidtop-agent can serve on a computer, by id. */
        val KNOWN: Map<String, String> = mapOf("f95checker" to "F95Checker's watched threads")
    }
}
