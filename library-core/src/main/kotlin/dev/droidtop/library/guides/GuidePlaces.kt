package dev.droidtop.library.guides

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A place in a guide: a page (PDF; 0 for text) and how far down it, 0 to 1. */
@Serializable
data class GuidePosition(val page: Int = 0, val fraction: Float = 0f)

/** A place the person marked, with what they called it. */
@Serializable
data class GuideBookmark(val position: GuidePosition, val label: String)

/**
 * What droidtop keeps for one game's guides (Droidtop/tracker#817): the guide last opened, where each was left, the
 * person's bookmarks and notes. Kept in droidtop's own data, never in the game's folder.
 */
@Serializable
data class GuidePlace(
    val last: String? = null,
    val positions: Map<String, GuidePosition> = emptyMap(),
    val bookmarks: Map<String, List<GuideBookmark>> = emptyMap(),
    val notes: String = "",
) {
    fun at(guideId: String): GuidePosition = positions[guideId] ?: GuidePosition()

    fun leftAt(guideId: String, position: GuidePosition): GuidePlace = copy(last = guideId, positions = positions + (guideId to position))

    fun marked(guideId: String, bookmark: GuideBookmark): GuidePlace =
        copy(bookmarks = bookmarks + (guideId to (bookmarks[guideId].orEmpty() + bookmark).distinctBy { it.position }))
}

/** One file per game under droidtop's files, named by a hash of the game's id. File work: off the main thread. */
object GuidePlaces {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context, gameId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(gameId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "guide-places"), "${digest.take(32)}.json")
    }

    fun load(context: Context, gameId: String): GuidePlace =
        runCatching { json.decodeFromString(GuidePlace.serializer(), file(context, gameId).readText()) }.getOrDefault(GuidePlace())

    fun save(context: Context, gameId: String, place: GuidePlace) {
        val target = file(context, gameId)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(json.encodeToString(GuidePlace.serializer(), place))
        temp.renameTo(target)
    }
}
