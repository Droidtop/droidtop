package dev.droidtop.stores.steam

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a game's save files looked like when they last matched Steam Cloud on
 * this device, and the pure rule that turns that and the two sides as they are
 * now into what to do (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313).
 *
 * The rule is Steam's own client rule, kept whole-game: if only this device
 * changed since the last sync the files go up, if only the cloud changed they
 * come down, if both changed and they still differ it is a conflict for the
 * person to settle. The last-synced state is what tells "this device changed"
 * from "the cloud changed", which neither side's timestamps can.
 */
internal object SteamCloudPlan {
    /** One file as it is on one side: the SHA-1 as hex, its size, and its modification time (epoch ms). */
    @Serializable
    data class Facts(val sha: String, val size: Long, val timeMs: Long)

    /** A side of a conflict: when it last changed, how many files, how many bytes. */
    data class Side(val timeMs: Long, val files: Int, val bytes: Long)

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

    /**
     * [base] is the SHA of each file at the last sync, [local] and [remote]
     * are the files now; all keyed by [CloudKey.Parsed.key].
     */
    fun decide(base: Map<String, String>, local: Map<String, Facts>, remote: Map<String, Facts>): Decision {
        // A file neither side has any more says nothing about either side.
        val keys = (base.keys + local.keys + remote.keys).filter { it in local || it in remote }
        if (keys.all { local[it]?.sha == remote[it]?.sha }) return Decision.UpToDate
        val localChanged = keys.any { local[it]?.sha != base[it] }
        val remoteChanged = keys.any { remote[it]?.sha != base[it] }
        return when {
            !localChanged -> Decision.Download(
                fetch = keys.filter { remote[it] != null && remote[it]?.sha != local[it]?.sha },
                removeLocal = keys.filter { remote[it] == null && local[it] != null },
            )
            !remoteChanged -> Decision.Upload(
                send = keys.filter { local[it] != null && local[it]?.sha != remote[it]?.sha },
                removeRemote = keys.filter { local[it] == null && remote[it] != null },
            )
            else -> Decision.Conflict(side(local.values), side(remote.values))
        }
    }

    private fun side(files: Collection<Facts>) =
        Side(timeMs = files.maxOfOrNull { it.timeMs } ?: 0L, files = files.size, bytes = files.sumOf { it.size })
}

/** The last-synced state of one game, kept in a small file (`steam/cloud/<app id>.json`). */
@Serializable
internal data class SteamCloudState(
    val files: Map<String, SteamCloudPlan.Facts> = emptyMap(),
) {
    /** The SHA of each file at the last sync. */
    val shas: Map<String, String> get() = files.mapValues { it.value.sha }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun file(filesDir: File, appId: Int): File = File(filesDir, "steam/cloud/$appId.json")

        fun encode(state: SteamCloudState): String = JSON.encodeToString(serializer(), state)

        fun decode(text: String): SteamCloudState = runCatching { JSON.decodeFromString(serializer(), text) }.getOrDefault(SteamCloudState())

        fun load(filesDir: File, appId: Int): SteamCloudState =
            file(filesDir, appId).takeIf { it.isFile }?.let { decode(it.readText()) } ?: SteamCloudState()

        fun save(filesDir: File, appId: Int, state: SteamCloudState) {
            val file = file(filesDir, appId)
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(encode(state))
            check(temp.renameTo(file)) { "Could not save the cloud save state" }
        }
    }
}
