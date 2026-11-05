package dev.droidtop.pluginhost

import android.content.Context
import dev.droidtop.net.AtomicWrite
import dev.droidtop.net.peer.Computer
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
 * the plugin changes records only through droidtop's ops and every change is
 * seen by the next sync.
 *
 * Two protocols (docs/plugin-api.md F8; droidtop-agent docs/DESIGN.md section 8;
 * Droidtop/tracker#794). Protocol 2, syncable data: the plugin publishes a whole
 * copy of its records ([publish]), droidtop makes it current in one rename, syncs
 * that copy with each computer by copies, and makes the merged copy current the
 * same way, so neither side ever reads half a copy and a stopped sync changes
 * nothing; the plugin brings a changed copy in itself ([copy], [onReceived]).
 * Protocol 1 (records changed one at a time with `context.put`) stays for a
 * context that has never been published, until every adapter speaks protocol 2.
 */
class PluginContexts(
    private val root: () -> File,
    private val computers: () -> List<Computer>,
    private val call: (Computer, String, JSONObject) -> JSONObject,
    private val noteSync: (Computer, String) -> Unit,
) {
    constructor(appContext: Context) : this(
        root = { PluginStore.root(appContext) },
        computers = { Computers.list(appContext) },
        call = { computer, op, args -> Computers.call(appContext, computer, op, args) },
        noteSync = { computer, line -> Computers.noteSync(appContext, computer.id, line) },
    )

    /** A context's state on this device: the plugin's declaration, its records and the fields waiting for the person. */
    data class State(val decl: JSONObject, val records: JSONObject, val conflicts: JSONArray)

    /**
     * A context's published copy (protocol 2): what syncs, never the plugin's live data. [generation] counts its
     * versions here. A data kind's copy (docs/plugin-api.md 3 H3, Droidtop/tracker#793) also carries [files], the
     * binary part a record cannot hold (Syncthing's key and index database), kept under the folder [fileset] names.
     */
    data class Copy(val generation: Long, val records: JSONObject, val files: List<CopyFile> = emptyList(), val fileset: String? = null)

    /** One file of a copy, as the plugin declared it and droidtop checked it. */
    data class CopyFile(val name: String, val size: Long, val sha256: String) {
        fun toJson(): JSONObject = JSONObject().put("name", name).put("size", size).put("sha256", sha256)

        companion object {
            fun fromJson(json: JSONObject): CopyFile? {
                val name = json.optString("name").takeIf { FILE_NAME.matches(it) } ?: return null
                val sha = json.optString("sha256").lowercase().takeIf { SHA256.matches(it) } ?: return null
                val size = json.optLong("size", -1L).takeIf { it >= 0 } ?: return null
                return CopyFile(name, size, sha)
            }
        }
    }

    private fun dir(pluginId: String): File = File(root(), "$pluginId/contexts").apply { mkdirs() }

    private fun copyFile(pluginId: String, contextId: String) = File(dir(pluginId), "$contextId.copy.json")

    /** Where the plugin writes a copy's files before it publishes them; never read as a copy. */
    private fun stagingDir(pluginId: String, contextId: String) = File(dir(pluginId), "$contextId.staging")

    /** Each fileset of a context's copies, one folder each; only the current one and the one before are kept. */
    private fun filesRoot(pluginId: String, contextId: String) = File(dir(pluginId), "$contextId.files")

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
        writeAtomically(stateFile(pluginId, contextId), JSONObject().put("decl", state.decl).put("records", state.records).put("conflicts", state.conflicts).toString())
    }

    /** Every file here is replaced whole, written beside it and renamed over it: a crash leaves the old one or the new one. */
    private fun writeAtomically(file: File, text: String) {
        try {
            AtomicWrite.text(file, text)
        } catch (e: java.io.IOException) {
            throw BrokerException(PluginErrorCode.FAILED, "the context could not be saved")
        }
    }

    /** The context's current copy, or null before the plugin first publishes one. */
    @Synchronized
    fun copy(pluginId: String, contextId: String): Copy? {
        val file = copyFile(pluginId, contextId)
        if (!file.isFile) return null
        return runCatching {
            JSONObject(file.readText()).let { o ->
                val files = o.optJSONArray("files")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(CopyFile::fromJson) } }.orEmpty()
                Copy(o.getLong("generation"), o.getJSONObject("records"), files, o.optNullable("fileset"))
            }
        }.getOrNull()
    }

    private fun saveCopy(pluginId: String, contextId: String, copy: Copy) {
        val json = JSONObject().put("generation", copy.generation).put("records", copy.records)
        if (copy.files.isNotEmpty()) json.put("files", JSONArray(copy.files.map { it.toJson() })).put("fileset", copy.fileset)
        writeAtomically(copyFile(pluginId, contextId), json.toString())
    }

    /**
     * The file [name] the plugin writes for its next copy of [contextId], in the context's staging folder: what
     * `context.stage` hands it a descriptor to. A name is one plain file name ([FILE_NAME]).
     */
    @Synchronized
    fun stagingFile(pluginId: String, contextId: String, name: String): File {
        opened(pluginId, contextId)
        if (!FILE_NAME.matches(name)) throw BrokerException(PluginErrorCode.INVALID_ARGS, FILE_NAME_RULE)
        return File(stagingDir(pluginId, contextId).apply { mkdirs() }, name)
    }

    /** The file [name] of [contextId]'s current copy, or null when the copy has none by that name. */
    @Synchronized
    fun copyFileOf(pluginId: String, contextId: String, name: String): File? {
        val copy = copy(pluginId, contextId) ?: return null
        if (copy.files.none { it.name == name }) return null
        val set = copy.fileset ?: return null
        return File(filesRoot(pluginId, contextId), "$set/$name").takeIf { it.isFile }
    }

    /**
     * Moves [files] from the staging folder into a new fileset, each checked against its declared size and SHA-256,
     * and returns the fileset's name; nothing becomes current here. A file that is missing or differs refuses all of
     * them, and the staging folder is left as it was for the plugin to write again.
     */
    private fun takeStaged(pluginId: String, contextId: String, files: List<CopyFile>): String? {
        if (files.isEmpty()) return null
        if (files.size > MAX_FILES) throw BrokerException(PluginErrorCode.INVALID_ARGS, "a copy carries at most $MAX_FILES files")
        if (files.map { it.name }.toSet().size != files.size) throw BrokerException(PluginErrorCode.INVALID_ARGS, "each file of a copy has its own name")
        val staging = stagingDir(pluginId, contextId)
        for (f in files) {
            val file = File(staging, f.name)
            if (!file.isFile) throw BrokerException(PluginErrorCode.INVALID_ARGS, "${f.name} was not written: stage it first")
            if (file.length() != f.size || sha256(file) != f.sha256) {
                throw BrokerException(PluginErrorCode.INVALID_ARGS, "${f.name} is not the file declared: its size or SHA-256 differs")
            }
        }
        val set = newFileset(pluginId, contextId)
        val target = File(filesRoot(pluginId, contextId), set).apply { mkdirs() }
        for (f in files) {
            if (!File(staging, f.name).renameTo(File(target, f.name))) {
                target.deleteRecursively()
                throw BrokerException(PluginErrorCode.FAILED, "the copy's files could not be kept")
            }
        }
        return set
    }

    private fun newFileset(pluginId: String, contextId: String): String {
        val root = filesRoot(pluginId, contextId)
        var n = (root.list()?.mapNotNull { it.toLongOrNull() }?.maxOrNull() ?: 0L) + 1
        while (File(root, n.toString()).exists()) n++
        return n.toString()
    }

    /** Leaves the current fileset and [previous] (going back is the person's choice), and removes every other. */
    private fun pruneFilesets(pluginId: String, contextId: String, current: String?, previous: String?) {
        filesRoot(pluginId, contextId).listFiles()?.forEach { if (it.name != current && it.name != previous) it.deleteRecursively() }
    }

    /**
     * The plugin's `updated` (protocol 2): [records] become the context's copy whole, in one rename, as the next
     * generation. The context then syncs by copies.
     */
    @Synchronized
    fun publish(pluginId: String, contextId: String, records: JSONObject, files: List<CopyFile> = emptyList()): Copy {
        opened(pluginId, contextId)
        val before = copy(pluginId, contextId)
        val set = takeStaged(pluginId, contextId, files)
        val copy = Copy((before?.generation ?: 0L) + 1, records, files, set)
        // The copy file's one rename makes records and files current together: never one without the other.
        saveCopy(pluginId, contextId, copy)
        pruneFilesets(pluginId, contextId, set, before?.fileset)
        return copy
    }

    /**
     * Gives [toPlugin] [fromPlugin]'s current copy of data kind [kind] as its own next copy (docs/plugin-api.md 3 H3,
     * Droidtop/tracker#793): the records whole, and each file copied into a fileset of the receiver's and checked
     * again, then made current in one rename. The source is only read. Returns the receiver's new copy, or null when
     * the source has no copy. Opens the receiver's context with the source's declaration when it has none.
     */
    @Synchronized
    fun transfer(fromPlugin: String, toPlugin: String, kind: String): Copy? {
        val source = copy(fromPlugin, kind) ?: return null
        val declared = load(fromPlugin, kind)?.decl ?: JSONObject().put("id", kind).put("fields", JSONArray()).put("presence", "both")
        if (load(toPlugin, kind) == null) open(toPlugin, kind, declared)
        val before = copy(toPlugin, kind)
        var set: String? = null
        if (source.files.isNotEmpty()) {
            val from = File(filesRoot(fromPlugin, kind), source.fileset ?: throw BrokerException(PluginErrorCode.FAILED, "the copy's files are missing"))
            set = newFileset(toPlugin, kind)
            val target = File(filesRoot(toPlugin, kind), set).apply { mkdirs() }
            for (f in source.files) {
                val dest = File(target, f.name)
                File(from, f.name).copyTo(dest, overwrite = true)
                if (dest.length() != f.size || sha256(dest) != f.sha256) {
                    target.deleteRecursively()
                    throw BrokerException(PluginErrorCode.FAILED, "${f.name} changed while it was copied")
                }
            }
        }
        val copy = Copy((before?.generation ?: 0L) + 1, JSONObject(source.records.toString()), source.files, set)
        saveCopy(toPlugin, kind, copy)
        pruneFilesets(toPlugin, kind, set, before?.fileset)
        return copy
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
     * [adapter] is the program the plugin's manifest offers for this context
     * (HostContextApis.adapterOffer), for a computer that has none yet.
     * Blocks on the network: the broker runs it on a binder thread, never the
     * main thread. Returns one line per computer and the conflicts left.
     */
    @Synchronized
    fun sync(pluginId: String, contextId: String, adapter: JSONObject? = null): JSONObject {
        var state = opened(pluginId, contextId)
        if (copy(pluginId, contextId) != null) return syncCopies(pluginId, contextId, state)
        val lines = JSONArray()
        for (computer in computers()) {
            val baselineFile = baselineFile(pluginId, contextId, computer.id)
            val baseline = runCatching { JSONObject(baselineFile.readText()) }.getOrDefault(JSONObject())
            val reply = call(
                computer,
                "sync_context",
                JSONObject().put("decl", state.decl).put("device", state.records).put("baseline", baseline).apply { adapter?.let { put("adapter", it) } },
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
            writeAtomically(baselineFile, (reply.optJSONObject("baseline") ?: JSONObject()).toString())
            val line = when {
                reply.optBoolean("deferred") -> "${computer.name}: ${reply.optString("message").ifBlank { "changes wait until the app there is closed" }}"
                else -> "${computer.name}: in step (${reply.optInt("sent")} changes sent, ${theirs.length()} to settle)"
            }
            lines.put(line)
            noteSync(computer, line)
        }
        if (lines.length() == 0) lines.put(NO_COMPUTER)
        return JSONObject().put("lines", lines).put("conflicts", state.conflicts.length())
    }

    /**
     * Protocol 2: the copy synced with each computer by copies (the agent's `sync_context_copy`: read the computer's
     * copy, merge three ways against this computer's baseline, put the merged copy back in one step, three tries on
     * `stale`). The merged records become this device's copy in one rename, then the baseline does. A computer that
     * fails changes nothing here. When the copy ends up different from what the plugin published, [onReceived] tells
     * it, to bring the copy in when that is safe.
     */
    private fun syncCopies(pluginId: String, contextId: String, opened: State): JSONObject {
        var state = opened
        val published = copy(pluginId, contextId)!!
        var current = published
        val lines = JSONArray()
        for (computer in computers()) {
            val baselineFile = baselineFile(pluginId, contextId, computer.id)
            val baseline = runCatching { JSONObject(baselineFile.readText()) }.getOrDefault(JSONObject())
            val reply = call(computer, "sync_context_copy", JSONObject().put("decl", state.decl).put("device", current.records).put("baseline", baseline))
            val failure = reply.optString("error").ifBlank { reply.optString("unreachable") }
            if (failure.isNotBlank()) {
                val line = "${computer.name}: ${if (failure.contains("unknown op", ignoreCase = true)) OLD_AGENT else failure}"
                lines.put(line)
                noteSync(computer, line)
                continue
            }
            val merged = reply.optJSONObject("device") ?: current.records
            if (canonical(merged) != canonical(current.records)) {
                current = Copy(current.generation + 1, merged, current.files, current.fileset)
                saveCopy(pluginId, contextId, current)
            }
            // A crash between the copy and the baseline costs nothing: the next merge finds both sides equal.
            writeAtomically(baselineFile, (reply.optJSONObject("baseline") ?: JSONObject()).toString())
            val conflicts = JSONArray()
            for (i in 0 until state.conflicts.length()) {
                val c = state.conflicts.optJSONObject(i) ?: continue
                if (c.optString("computer_id") != computer.id) conflicts.put(c)
            }
            val theirs = reply.optJSONArray("conflicts") ?: JSONArray()
            for (i in 0 until theirs.length()) {
                theirs.optJSONObject(i)?.let { conflicts.put(it.put("computer_id", computer.id).put("computer_name", computer.name)) }
            }
            state = State(state.decl, state.records, conflicts)
            save(pluginId, contextId, state)
            val line = if (reply.optBoolean("deferred")) {
                "${computer.name}: ${reply.optString("message").ifBlank { "the copy waits there until the app is closed" }}"
            } else {
                "${computer.name}: in step (${reply.optInt("sent")} changes sent, ${theirs.length()} to settle)"
            }
            lines.put(line)
            noteSync(computer, line)
        }
        if (lines.length() == 0) lines.put(NO_COMPUTER)
        if (current.generation != published.generation) onReceived?.invoke(pluginId, contextId, current.generation)
        return JSONObject().put("lines", lines).put("conflicts", state.conflicts.length()).put("generation", current.generation)
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
        writeAtomically(baselineFile, baseline.toString())
        save(pluginId, contextId, State(state.decl, state.records, left))
        return true
    }

    companion object {
        /** A copy file's name: one plain name, no folder, not hidden. */
        val FILE_NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,63}$")
        const val FILE_NAME_RULE = "name is one file name of up to 64 letters, digits, '.', '_' and '-', not starting with '.'"
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** A copy carries a handful of files (Syncthing's are four); a record holds the rest. */
        const val MAX_FILES = 16

        /** A file's SHA-256, read in pieces: an index database is too large to hold in memory. */
        fun sha256(file: File): String = dev.droidtop.shared.trust.Sha256.hex(file)

        private const val NO_COMPUTER = "No computer is paired: pair one under Settings > Accounts and sources > Computers"
        private const val OLD_AGENT = "this droidtop's sync library is older than this context's sync; update droidtop"

        /**
         * Tells a plugin that a merged copy of its context was made current here (the event `context.received`). Set
         * at start by library-core's event bus; called on the sync's thread.
         */
        @Volatile
        var onReceived: ((pluginId: String, contextId: String, generation: Long) -> Unit)? = null

        /** [value] as text with every object's keys in order, so two copies compare by what they hold. */
        fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(value)
            is Number -> JSONObject.numberToString(value)
            else -> value.toString()
        }
    }
}
