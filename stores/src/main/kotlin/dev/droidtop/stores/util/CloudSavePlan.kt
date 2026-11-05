package dev.droidtop.stores.util

import dev.droidtop.library.stores.SaveChoice
import dev.droidtop.library.stores.SaveSide
import dev.droidtop.net.AtomicWrite
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one rule every store's cloud save sync decides by (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313 and #224):
 * what a game's save files looked like when they last matched the store's cloud on this device, and the two sides as
 * they are now, turned into what to do.
 *
 * The rule is the store clients' own, kept whole-game: if only this device changed since the last sync the files go
 * up, if only the cloud changed they come down, if both changed and they still differ it is a conflict for the person
 * to settle. The last-synced state is what tells "this device changed" from "the cloud changed", which neither side's
 * timestamps can.
 *
 * Each side keeps its own fingerprint of a file: Steam Cloud and Epic list a file's SHA-1, so both sides are the same
 * hash; GOG lists the MD5 of the gzip it stores, which this device can only reproduce for a file gzipped the way
 * droidtop gzips. So the state keeps both fingerprints of each file at the last sync, and a file is unchanged on a side
 * when that side's fingerprint is the one kept.
 */
internal object CloudSavePlan {
    /** One file as it is on one side: its fingerprint ([sha], a hex hash), its size, and its modification time (epoch ms). */
    @Serializable
    data class Facts(val sha: String, val size: Long, val timeMs: Long)

    /** A file's two fingerprints at the last sync; null for a side that did not have it. */
    data class Base(val local: String?, val remote: String?)

    /** A side of a conflict: when it last changed, how many files, how many bytes. */
    data class Side(val timeMs: Long, val files: Int, val bytes: Long) {
        fun asSaveSide(): SaveSide = SaveSide(timeMs, files, bytes)
    }

    sealed interface Decision {
        /** Both sides hold the same files. */
        data object UpToDate : Decision

        /** This device has the older state: [fetch] come down, [removeLocal] go (the cloud no longer has them). */
        data class Download(val fetch: List<String>, val removeLocal: List<String>) : Decision

        /** The cloud has the older state: [send] go up, [removeRemote] are deleted there. */
        data class Upload(val send: List<String>, val removeRemote: List<String>) : Decision

        /** Both sides changed since the last sync and differ. */
        data class Conflict(val local: Side, val cloud: Side) : Decision
    }

    /** A base for a store whose two sides use the same hash: each file's one fingerprint on both sides. */
    fun same(shas: Map<String, String>): Map<String, Base> = shas.mapValues { Base(it.value, it.value) }

    /**
     * [base] is each file's fingerprints at the last sync, [local] and [remote] are the files now; all keyed by the
     * file's path as the cloud names it.
     */
    fun decide(base: Map<String, Base>, local: Map<String, Facts>, remote: Map<String, Facts>): Decision {
        // A file neither side has any more says nothing about either side.
        val keys = (base.keys + local.keys + remote.keys).filter { it in local || it in remote }
        val open = keys.filterNot { agreed(base[it], local[it], remote[it]) }
        if (open.isEmpty()) return Decision.UpToDate
        val localChanged = open.any { local[it]?.sha != base[it]?.local }
        val remoteChanged = open.any { remote[it]?.sha != base[it]?.remote }
        return when {
            !localChanged -> Decision.Download(
                fetch = open.filter { remote[it] != null },
                removeLocal = open.filter { remote[it] == null && local[it] != null },
            )
            !remoteChanged -> Decision.Upload(
                send = open.filter { local[it] != null },
                removeRemote = open.filter { local[it] == null && remote[it] != null },
            )
            else -> Decision.Conflict(side(local.values), side(remote.values))
        }
    }

    /**
     * The decision once the person picked a side of a conflict: the side they kept is treated as the only one that
     * changed, so it wins file by file, and the other side's extra files are removed.
     */
    fun settle(choice: SaveChoice, local: Map<String, Facts>, remote: Map<String, Facts>): Decision {
        val keys = local.keys + remote.keys
        val base = when (choice) {
            // The cloud as it is, this device as something new.
            SaveChoice.LOCAL -> keys.associateWith { Base(local = CHANGED, remote = remote[it]?.sha) }
            SaveChoice.CLOUD -> keys.associateWith { Base(local = local[it]?.sha, remote = CHANGED) }
        }
        return decide(base, local, remote)
    }

    /** A fingerprint no file has: the side it stands for counts as changed. */
    private const val CHANGED = "\u0000"

    private fun agreed(base: Base?, local: Facts?, remote: Facts?): Boolean {
        if (local == null && remote == null) return true
        if (local != null && remote != null && local.sha == remote.sha) return true
        return base != null && local?.sha == base.local && remote?.sha == base.remote
    }

    private fun side(files: Collection<Facts>) =
        Side(timeMs = files.maxOfOrNull { it.timeMs } ?: 0L, files = files.size, bytes = files.sumOf { it.size })
}

/**
 * The last-synced state of one game's cloud saves, kept in a small file per game ([file]): each file's facts on this
 * device and, for a store whose cloud names it by another hash, the cloud's fingerprint ([remote]; a file with none
 * there has the same fingerprint on both sides, which is all Steam keeps).
 */
@Serializable
internal data class CloudSaveState(
    val files: Map<String, CloudSavePlan.Facts> = emptyMap(),
    val remote: Map<String, String> = emptyMap(),
) {
    /** The SHA of each file on this device at the last sync. */
    val shas: Map<String, String> get() = files.mapValues { it.value.sha }

    /** Each file's two fingerprints at the last sync. */
    val base: Map<String, CloudSavePlan.Base> get() = files.mapValues { (key, facts) -> CloudSavePlan.Base(facts.sha, remote[key] ?: facts.sha) }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Where [store]'s state of game [id] is kept: `<store>/cloud/<id>.json` in the app's files. */
        fun file(filesDir: File, store: String, id: String): File = File(filesDir, "$store/cloud/${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

        fun encode(state: CloudSaveState): String = JSON.encodeToString(serializer(), state)

        fun decode(text: String): CloudSaveState = runCatching { JSON.decodeFromString(serializer(), text) }.getOrDefault(CloudSaveState())

        fun load(file: File): CloudSaveState = file.takeIf { it.isFile }?.let { decode(it.readText()) } ?: CloudSaveState()

        fun save(file: File, state: CloudSaveState) {
            AtomicWrite.text(file, encode(state))
        }
    }
}
