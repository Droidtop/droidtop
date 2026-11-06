/*
 * GIFs and stickers from the person's own folders (docs/SPEC.md 6a, "GIFs and stickers", Droidtop/tracker#903 H5): the
 * Android side of GifLibrary.kt. The insert through the editor's content channel follows CleverKeys' KeyboardReceiver
 * (`insertGif`, `commitGifMedia`), https://github.com/tribixbite/CleverKeys at c8cbb74b, GPL-3.0 (Copyright (C)
 * tribixbite and contributors); its first-frame thumbnail memory cap follows GifGridManager's. The folder index, the
 * folder list and the settings row are droidtop's own.
 */
package org.pocketworkstation.pckeyboard

import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.LruCache
import android.widget.ImageView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The index of the picked folders, one per process: kept in `files/hkr-gifs/index.tsv` ([GifIndexFile]), read and
 * written on its own thread, never on the main thread. Callbacks run on the main thread.
 */
object GifIndex {
    /** At most this many images are listed, over every folder together. */
    const val MAX_ITEMS = 5000

    /** Folders inside a picked folder are read this deep. */
    private const val MAX_DEPTH = 6

    private val io = KeyboardThreads.single("hk-gif-index")
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var items: List<GifItem>? = null
    private var loading = false
    private val waiting = ArrayList<(List<GifItem>) -> Unit>()

    /** What a scan found: how many images, and the folders it could not read (their permission gone, or removed). */
    data class Scan(val images: Int, val unreadable: List<String>)

    private fun file(context: Context) = File(File(context.filesDir, "hkr-gifs"), "index.tsv")

    /** The picked folders' tree URIs. Reads the preferences; call it off the main thread where it is the first read. */
    fun folders(context: Context): List<String> = GifFolderList.decode(ToolsPrefs.prefs(context).getString(ToolsPrefs.GIF_FOLDERS, null))

    /** The index, read from its file the first time (scanned when there is none yet); [onLoaded] on the main thread. */
    fun load(context: Context, onLoaded: (List<GifItem>) -> Unit) {
        items?.let {
            onLoaded(it)
            return
        }
        waiting += onLoaded
        if (loading) return
        loading = true
        val app = context.applicationContext
        io.execute {
            val saved = logged("GIF index not read") { file(app).takeIf { it.isFile }?.readText()?.let(GifIndexFile::decode) }.getOrNull()
            val found = saved ?: if (folders(app).isEmpty()) emptyList() else scanAll(app).first
            if (saved == null && found.isNotEmpty()) save(app, found)
            publish(found)
        }
    }

    /** Reads every picked folder again (Rescan, and after a folder is added); [onDone] on the main thread. */
    fun rescan(context: Context, onDone: ((Scan) -> Unit)? = null) {
        val app = context.applicationContext
        io.execute {
            val (found, unreadable) = scanAll(app)
            save(app, found)
            publish(found) { onDone?.invoke(Scan(found.size, unreadable)) }
        }
    }

    /**
     * Keeps read access to [tree], a folder picked with the system's folder picker, adds it to the list and scans.
     * [onDone] gets null when Android would not let the keyboard keep reading it.
     */
    fun addFolder(context: Context, tree: Uri, onDone: (Scan?) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val kept = logged("GIF folder $tree not kept") {
                app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (!kept) {
                main.post { onDone(null) }
                return@execute
            }
            val prefs = ToolsPrefs.prefs(app)
            prefs.edit().putString(ToolsPrefs.GIF_FOLDERS, GifFolderList.encode(folders(app) + tree.toString())).commit()
            rescan(app) { onDone(it) }
        }
    }

    /** Takes [tree] off the list, gives its read access back, and drops its images from the index. */
    fun removeFolder(context: Context, tree: String, onDone: () -> Unit) {
        val app = context.applicationContext
        io.execute {
            logged("GIF folder $tree not released") {
                app.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ToolsPrefs.prefs(app).edit().putString(ToolsPrefs.GIF_FOLDERS, GifFolderList.encode(folders(app) - tree)).commit()
            val kept = (items ?: logged("GIF index not read") { file(app).takeIf { it.isFile }?.readText()?.let(GifIndexFile::decode) }.getOrNull().orEmpty())
                .filter { it.folder != tree }
            save(app, kept)
            publish(kept) { onDone() }
        }
    }

    /** A folder's name for the settings list: what its folder says, else the end of its path. Off the main thread. */
    fun folderName(resolver: ContentResolver, tree: String): String {
        val uri = Uri.parse(tree)
        val id = logged("GIF folder $tree has no id") { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return tree
        return displayName(resolver, DocumentsContract.buildDocumentUriUsingTree(uri, id)) ?: GifFolderList.label(id)
    }

    private fun publish(found: List<GifItem>, then: (() -> Unit)? = null) {
        main.post {
            items = found
            loading = false
            val callbacks = ArrayList(waiting)
            waiting.clear()
            callbacks.forEach { it(found) }
            then?.invoke()
        }
    }

    private fun save(context: Context, found: List<GifItem>) {
        logged("GIF index not saved") {
            val target = file(context)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "index.tsv.tmp")
            temp.writeText(GifIndexFile.encode(found))
            if (!temp.renameTo(target)) error("rename failed")
        }
    }

    private fun scanAll(context: Context): Pair<List<GifItem>, List<String>> {
        val found = ArrayList<GifItem>()
        val unreadable = ArrayList<String>()
        for (folder in folders(context)) {
            if (found.size >= MAX_ITEMS) break
            val got = logged("GIF folder $folder not read") { scan(context.contentResolver, folder, MAX_ITEMS - found.size) }.getOrNull()
            if (got == null) unreadable += folder else found += got
        }
        return found to unreadable
    }

    /**
     * The GIF, WebP and PNG files in [tree] and its folders, by name within each folder. Each is referenced by its
     * document URI; only a WebP's first 21 bytes are read, to tell an animated one ([GifFormats.isAnimatedWebp]).
     */
    private fun scan(resolver: ContentResolver, tree: String, max: Int): List<GifItem> {
        val treeUri = Uri.parse(tree)
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = displayName(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)) ?: GifFolderList.label(rootId)
        val found = ArrayList<GifItem>()
        // A folder to read: its document id, the words of every folder from the picked one down, and its own name.
        val queue = ArrayDeque<Triple<String, List<String>, String>>()
        queue += Triple(rootId, GifNames.words(rootName, dropExtension = false), rootName)
        val depth = HashMap<String, Int>().apply { put(rootId, 0) }
        while (queue.isNotEmpty() && found.size < max) {
            val (id, pathWords, folderName) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val rows = ArrayList<Triple<String, String, String?>>()
            // A folder whose listing throws (its permission gone) makes the caller count the whole picked folder as
            // unreadable; a listing that answers null is a folder gone mid-scan, and is skipped.
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val childId = c.getString(0)
                    val name = c.getString(1)
                    if (childId != null && name != null) rows += Triple(childId, name, c.getString(2))
                }
            }
            for ((childId, name, mime) in rows.sortedBy { it.second.lowercase() }) {
                if (name.startsWith(".")) continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val d = (depth[id] ?: 0) + 1
                    if (d <= MAX_DEPTH) {
                        depth[childId] = d
                        queue += Triple(childId, pathWords + GifNames.words(name, dropExtension = false), name)
                    }
                    continue
                }
                val type = GifFormats.accepted(name, mime) ?: continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                val animated = type == GifFormats.WEBP && logged("WebP header of $name not read") {
                    resolver.openInputStream(uri)?.use { input ->
                        val header = ByteArray(GifFormats.WEBP_HEADER)
                        var read = 0
                        while (read < header.size) {
                            val n = input.read(header, read, header.size - read)
                            if (n < 0) break
                            read += n
                        }
                        GifFormats.isAnimatedWebp(header.copyOf(read))
                    } ?: false
                }.getOrDefault(false)
                found += GifItem(
                    uri = uri.toString(),
                    mime = type,
                    kind = GifFormats.kindOf(type, animated, pathWords),
                    name = GifNames.displayName(name),
                    words = GifNames.words(name),
                    tags = GifNames.words(folderName, dropExtension = false),
                    folder = tree,
                )
                if (found.size >= max) break
            }
        }
        return found
    }

    private fun displayName(resolver: ContentResolver, document: Uri): String? = logged("no name for $document") {
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

/** The local library behind the panel's GIFs and Stickers tabs: the only [GifProvider]. */
class LocalGifLibrary(context: Context) : GifProvider {
    private val app = context.applicationContext

    override fun search(query: String, kind: GifKind, recent: List<String>, limit: Int, onResult: (List<GifItem>) -> Unit) {
        GifIndex.load(app) { all -> onResult(GifSearch.rank(all.filter { it.kind == kind }, query, recent, limit)) }
    }
}

/**
 * First-frame thumbnails, decoded on their own thread no larger than the cell, kept in a small memory cache (8 MB at
 * most, as CleverKeys caps its grid's cache rather than take an image loader's default). Nothing is written to disk,
 * least of all next to the person's files. Main thread only, apart from the decoding.
 */
object GifThumbs {
    private val io = KeyboardThreads.single("hk-gif-thumbs")
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(cacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.allocationByteCount / 1024)
    }

    // Which image each cell wants now (main thread), and the same as a set the decoder can read: an image no cell
    // wants any more, scrolled past, is not decoded.
    private val wanted = WeakHashMap<ImageView, String>()
    private val wantedUris: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private fun cacheKb(): Int = minOf(8 * 1024, (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()).coerceAtLeast(1024)

    fun show(view: ImageView, item: GifItem, size: Int) {
        val previous = wanted.put(view, item.uri)
        if (previous != null && previous != item.uri && !wanted.containsValue(previous)) wantedUris.remove(previous)
        cache.get(item.uri)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        wantedUris += item.uri
        val app = view.context.applicationContext
        io.execute {
            if (item.uri !in wantedUris) return@execute
            val bitmap = cache.get(item.uri) ?: decode(app, item.uri, size)?.also { cache.put(item.uri, it) } ?: return@execute
            main.post { if (wanted[view] == item.uri) view.setImageBitmap(bitmap) }
        }
    }

    private fun decode(context: Context, uri: String, size: Int): Bitmap? = logged("no thumbnail for $uri") {
        val source = Uri.parse(uri)
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            // The first frame only: decodeBitmap never animates.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source)) { decoder, info, _ ->
                val (w, h) = GifThumbSize.fit(info.size.width, info.size.height, size)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply { inSampleSize = GifThumbSize.sampleSize(bounds.outWidth, bounds.outHeight, size) }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    }.getOrNull()
}

/**
 * The one image picked, made ready for the editor. A document URI from a picked folder is not handed on: whether a
 * keyboard can pass its folder grant on to another app depends on the folder's provider, and some messaging apps
 * cannot read a tree document URI at all. So the picked image alone is copied into the keyboard's cache
 * (`cache/hkr-media-share/`) and served by [HkrMediaProvider] with a read grant for this insert; only the newest
 * [GifShareCache.KEEP] copies stay. Copying runs on its own thread; [prepare]'s answer comes on the main thread.
 */
object GifShare {
    /** An image larger than this is not inserted (messaging apps refuse far smaller ones). */
    const val MAX_BYTES = 24L * 1024 * 1024

    private val io = KeyboardThreads.single("hk-gif-share")
    private val main = Handler(Looper.getMainLooper())

    sealed class Ready {
        data class Shared(val uri: Uri) : Ready()

        object TooLarge : Ready()

        object Unreadable : Ready()
    }

    fun authority(context: Context): String = context.packageName + ".hkr.media"

    fun prepare(context: Context, item: GifItem, onReady: (Ready) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val result = logged("GIF ${item.uri} not copied for inserting") { copy(app, item) }.getOrDefault(Ready.Unreadable)
            main.post { onReady(result) }
        }
    }

    private fun copy(context: Context, item: GifItem): Ready {
        val dir = File(context.cacheDir, "hkr-media-share").apply { mkdirs() }
        dir.listFiles()?.let { files ->
            val stale = GifShareCache.stale(files.map { it.name to it.lastModified() }, GifShareCache.KEEP - 1).toSet()
            files.filter { it.name in stale }.forEach { it.delete() }
        }
        val extension = when (item.mime) {
            GifFormats.GIF -> "gif"
            GifFormats.WEBP -> "webp"
            else -> "png"
        }
        val out = File(dir, "hkr-${System.currentTimeMillis()}.$extension")
        val input = context.contentResolver.openInputStream(Uri.parse(item.uri)) ?: return Ready.Unreadable
        var total = 0L
        input.use { source ->
            out.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) break
                    sink.write(buffer, 0, n)
                }
            }
        }
        if (total > MAX_BYTES) {
            out.delete()
            return Ready.TooLarge
        }
        return Ready.Shared(FileProvider.getUriForFile(context, authority(context), out))
    }
}

/**
 * Serves the copies [GifShare] makes, read-only, to the app an image is inserted into. Its own class so the manifest
 * merger never meets another module's FileProvider entry.
 */
class HkrMediaProvider : FileProvider()

/** The editor an image goes into: only the input method has one ([ImeMediaTarget]); a panel droidtop draws has none. */
interface MediaTarget {
    fun editorPresent(): Boolean

    /** A password or no-learning field (IncognitoRules.sensitiveField): no GIFs or stickers there. */
    fun sensitive(): Boolean

    /** The MIME types the editor says it takes (EditorInfoCompat.getContentMimeTypes); empty when none. */
    fun contentMimeTypes(): Array<String>

    /** Commits [uri] as content of [mime]; false when the editor refused it. */
    fun commit(uri: Uri, mime: String, label: String): Boolean
}

/** The input method's editor: InputConnectionCompat.commitContent with a read grant to the editor's app. */
class ImeMediaTarget(private val ime: InputMethodService) : MediaTarget {
    override fun editorPresent(): Boolean = ime.currentInputConnection != null && ime.currentInputEditorInfo != null

    override fun sensitive(): Boolean {
        val info = ime.currentInputEditorInfo ?: return true
        return IncognitoRules.sensitiveField(info.inputType, info.imeOptions)
    }

    override fun contentMimeTypes(): Array<String> =
        ime.currentInputEditorInfo?.let { logged("no content types") { EditorInfoCompat.getContentMimeTypes(it) }.getOrNull() } ?: emptyArray()

    override fun commit(uri: Uri, mime: String, label: String): Boolean {
        val connection = ime.currentInputConnection ?: return false
        val info = ime.currentInputEditorInfo ?: return false
        val content = InputContentInfoCompat(uri, ClipDescription(label, arrayOf(mime)), null)
        return logged("image not committed") {
            InputConnectionCompat.commitContent(connection, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        }.getOrDefault(false)
    }
}

/**
 * The keyboard settings' "GIF and sticker folders" row (Droidtop/tracker#903 H5): the picked folders, each removable,
 * Add folder (the system's folder picker) and Rescan. What is going on shows on the row; a refusal is a dialog (no
 * toasts). Mirrors the Dictionaries row (DictionaryRow).
 */
internal object GifFoldersRow {
    const val KEY = ToolsPrefs.GIF_FOLDERS
    const val PICK_REQUEST = 9035

    private val worker by lazy { KeyboardThreads.single("keyboard-gif-folders-row") }

    @JvmStatic
    fun open(activity: android.app.Activity, row: android.preference.Preference) {
        worker.execute {
            val folders = GifIndex.folders(activity)
            val names = folders.map { GifIndex.folderName(activity.contentResolver, it) }
            activity.runOnUiThread { show(activity, row, folders, names) }
        }
    }

    private fun show(activity: android.app.Activity, row: android.preference.Preference, folders: List<String>, names: List<String>) {
        if (activity.isFinishing) return
        val labels = names.map { activity.getString(R.string.gif_folder_remove, it) } +
            activity.getString(R.string.gif_folder_add) +
            listOfNotNull(activity.getString(R.string.gif_folder_rescan).takeIf { folders.isNotEmpty() })
        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.title_gif_folders)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == folders.size -> {
                        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        activity.startActivityForResult(pick, PICK_REQUEST)
                    }
                    which == folders.size + 1 -> {
                        row.setSummary(R.string.gif_folders_scanning)
                        GifIndex.rescan(activity) { row.summary = scanned(activity, it) }
                    }
                    else -> GifIndex.removeFolder(activity, folders[which]) {
                        row.summary = activity.getString(R.string.gif_folder_removed, names[which])
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The folder the picker returned: kept, listed and scanned; the row says what was found. */
    @JvmStatic
    fun picked(activity: android.app.Activity, row: android.preference.Preference?, uri: Uri?) {
        if (uri == null) return
        row?.setSummary(R.string.gif_folders_scanning)
        GifIndex.addFolder(activity, uri) { scan ->
            if (scan == null) {
                row?.setSummary(R.string.summary_gif_folders)
                if (!activity.isFinishing) {
                    android.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.title_gif_folders)
                        .setMessage(R.string.gif_folder_not_kept)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } else {
                row?.summary = scanned(activity, scan)
            }
        }
    }

    private fun scanned(context: Context, scan: GifIndex.Scan): String {
        val found = context.getString(R.string.gif_folders_scanned, scan.images)
        return if (scan.unreadable.isEmpty()) found else found + " " + context.getString(R.string.gif_folders_unreadable, scan.unreadable.size)
    }
}
