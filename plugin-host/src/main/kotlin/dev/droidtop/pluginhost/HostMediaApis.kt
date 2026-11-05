package dev.droidtop.pluginhost

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * The person's music, videos, pictures and documents for a plugin (docs/plugin-api.md 3 A9, `media.read@1`,
 * Droidtop/tracker#714): a now-playing, gallery, backup or media-server plugin reads what Android's media library lists,
 * a page at a time, and opens one item read-only. droidtop runs the query itself (a contained plugin has no
 * filesystem of its own) and hands out a read-only descriptor, never a path. Both ops need `media.read`, a dangerous
 * permission.
 */
interface MediaLibrary {
    /** One page of [kind]'s items, newest first: at most [size], from [page] (0-based); [more] when there are further pages. */
    fun query(kind: MediaKind, page: Int, size: Int): Page

    /** [id]'s content, read-only; null when there is no such item. */
    fun open(id: MediaId): ParcelFileDescriptor?

    data class Item(
        val id: MediaId,
        val name: String,
        val mime: String?,
        val size: Long,
        val modifiedMs: Long,
        val durationMs: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
    )

    data class Page(val items: List<Item>, val more: Boolean)
}

enum class MediaKind(val word: String) {
    AUDIO("audio"), VIDEO("video"), IMAGE("image"), DOCUMENT("document");

    companion object {
        fun of(word: String?): MediaKind? = entries.firstOrNull { it.word == word }
    }
}

/** An item as the plugin names it: its kind and Android's row id, "image:123". */
data class MediaId(val kind: MediaKind, val row: Long) {
    override fun toString(): String = "${kind.word}:$row"

    companion object {
        /** "image:123" as an id; null for anything else. Pure. */
        fun parse(text: String?): MediaId? {
            val (word, row) = text?.trim()?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return null
            val kind = MediaKind.of(word) ?: return null
            return row.toLongOrNull()?.takeIf { it > 0 }?.let { MediaId(kind, it) }
        }
    }
}

/** Android's media library (MediaStore), as droidtop's own app can read it. */
class AndroidMediaLibrary(context: Context) : MediaLibrary {
    private val resolver = context.applicationContext.contentResolver

    private fun collection(kind: MediaKind): Uri = when (kind) {
        MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaKind.DOCUMENT -> MediaStore.Files.getContentUri("external")
    }

    override fun query(kind: MediaKind, page: Int, size: Int): MediaLibrary.Page {
        val columns = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (kind == MediaKind.AUDIO || kind == MediaKind.VIDEO) add("duration")
            if (kind == MediaKind.IMAGE || kind == MediaKind.VIDEO) { add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT) }
            if (kind == MediaKind.AUDIO) { add(MediaStore.Audio.AudioColumns.TITLE); add(MediaStore.Audio.AudioColumns.ARTIST); add(MediaStore.Audio.AudioColumns.ALBUM) }
        }.toTypedArray()
        // Documents: the files that are none of the other kinds (PDFs, text, archives' neighbours).
        val selection = if (kind == MediaKind.DOCUMENT) "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} AND ${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL" else null
        val items = ArrayList<MediaLibrary.Item>()
        var more = false
        resolver.query(collection(kind), columns, selection, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cursor ->
            if (!cursor.moveToPosition(page * size)) return MediaLibrary.Page(emptyList(), false)
            fun long(name: String) = cursor.getColumnIndex(name).takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) }
            fun string(name: String) = cursor.getColumnIndex(name).takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getString(it) }
            do {
                val row = long(MediaStore.MediaColumns._ID) ?: continue
                items += MediaLibrary.Item(
                    id = MediaId(kind, row),
                    name = string(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty(),
                    mime = string(MediaStore.MediaColumns.MIME_TYPE),
                    size = long(MediaStore.MediaColumns.SIZE) ?: 0L,
                    modifiedMs = (long(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L) * 1000,
                    durationMs = long("duration"),
                    width = long(MediaStore.MediaColumns.WIDTH)?.toInt(),
                    height = long(MediaStore.MediaColumns.HEIGHT)?.toInt(),
                    title = string(MediaStore.Audio.AudioColumns.TITLE).takeIf { kind == MediaKind.AUDIO },
                    artist = string(MediaStore.Audio.AudioColumns.ARTIST).takeIf { kind == MediaKind.AUDIO },
                    album = string(MediaStore.Audio.AudioColumns.ALBUM).takeIf { kind == MediaKind.AUDIO },
                )
            } while (items.size < size && cursor.moveToNext())
            more = !cursor.isAfterLast && cursor.moveToNext()
        }
        return MediaLibrary.Page(items, more)
    }

    override fun open(id: MediaId): ParcelFileDescriptor? =
        runCatching { resolver.openFileDescriptor(ContentUris.withAppendedId(collection(id.kind), id.row), "r") }.getOrNull()
}

internal object HostMediaApis {
    const val PERMISSION = "media.read"
    const val MAX_PAGE = 100

    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun library(env: BrokerEnvironment): MediaLibrary =
        env.media() ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop has no media library to offer")

    /** The kind asked for, or a refusal naming the kinds. Pure. */
    fun kind(args: JSONObject): MediaKind =
        MediaKind.of(args.optString("kind")) ?: invalid("kind is one of ${MediaKind.entries.joinToString { it.word }}")

    /** The page asked for (0-based) and its size, 1 to [MAX_PAGE] (50 when not said). Pure. */
    fun paging(args: JSONObject): Pair<Int, Int> {
        val page = args.optInt("page", 0)
        if (page < 0 || page > 100_000) invalid("page is 0 or more")
        return page to args.optInt("size", 50).coerceIn(1, MAX_PAGE)
    }

    fun itemJson(item: MediaLibrary.Item): JSONObject = JSONObject()
        .put("id", item.id.toString()).put("name", item.name).put("mime", item.mime ?: JSONObject.NULL)
        .put("size", item.size).put("modifiedMs", item.modifiedMs)
        .apply {
            item.durationMs?.let { put("durationMs", it) }
            item.width?.let { put("width", it) }
            item.height?.let { put("height", it) }
            item.title?.let { put("title", it) }
            item.artist?.let { put("artist", it) }
            item.album?.let { put("album", it) }
        }

    val ops: List<HostOp> = listOf(
        HostOp("media", "query", permission = PERMISSION, target = { it.optString("kind") }) { env, _, args ->
            val kind = kind(args)
            val (page, size) = paging(args)
            val answer = library(env).query(kind, page, size)
            JSONObject().put("items", JSONArray(answer.items.map(::itemJson))).put("more", answer.more)
        },
        // One item's content, read-only: a descriptor, never a path.
        HostOp(
            "media", "open", permission = PERMISSION, target = { it.optString("id") },
            open = { env, _, args ->
                val id = MediaId.parse(args.optString("id")) ?: invalid("id is an item's id from media.query")
                library(env).open(id) ?: throw BrokerException(PluginErrorCode.NOT_FOUND, "no such item")
            },
        ),
    )
}
