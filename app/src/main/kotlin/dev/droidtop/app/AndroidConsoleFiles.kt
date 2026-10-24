package dev.droidtop.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.droidtop.library.credentials.handoff.ConsoleFiles
import dev.droidtop.library.credentials.handoff.FileNames
import dev.droidtop.library.credentials.handoff.IncomingFile
import dev.droidtop.library.credentials.handoff.OfferedFile
import java.io.IOException
import java.io.OutputStream

/**
 * The folder the person chose for files sent from another device (docs/SPEC.md, "Type from another device", file
 * transfer, Droidtop/tracker#645): a tree the system folder picker handed back, kept by its persisted permission, or
 * none, which means Download/droidtop. Droidtop never picks a folder itself, so a game folder is written into only
 * when the person chose it here.
 */
internal object ReceiveFolder {
    private const val PREFS = "droidtop_remote_input"
    private const val KEY_TREE = "receive_tree"
    const val DEFAULT_LABEL = "Download/droidtop"
    const val DEFAULT_PATH = "Download/droidtop/"

    fun tree(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)

    /** Where files go, in words: the chosen folder's name, else the default (below Android 10 there is no default). */
    fun label(context: Context): String {
        val tree = tree(context) ?: return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DEFAULT_LABEL else "none chosen"
        return runCatching { FileNames.folderLabel(DocumentsContract.getTreeDocumentId(tree)) }.getOrDefault("the chosen folder")
    }

    /** Remembers [tree] (the folder picker's result, whose permission the caller has already taken), or the default for null. */
    fun choose(context: Context, tree: Uri?) {
        val old = tree(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (tree == null) prefs.remove(KEY_TREE) else prefs.putString(KEY_TREE, tree.toString())
        prefs.apply()
        if (old != null && old != tree) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }
}

/**
 * [ConsoleFiles] on Android's storage framework, without any storage permission: a chosen folder through its document
 * tree, else Download/droidtop through MediaStore (Android 10 and later; a file is pending, and so unseen by other apps,
 * until all of it has arrived). Called from the server's thread, never the main thread. A failure is null or an
 * IOException, never an unchecked crash, and a file that did not arrive is deleted.
 */
internal class AndroidConsoleFiles(private val context: Context) : ConsoleFiles {
    private val resolver get() = context.contentResolver

    override fun folderLabel(): String = ReceiveFolder.label(context)

    override fun exists(name: String): Boolean {
        val tree = ReceiveFolder.tree(context)
        return try {
            if (tree != null) existsInTree(tree, name) else existsInDownloads(name)
        } catch (e: RuntimeException) {
            false
        }
    }

    override fun create(name: String): IncomingFile? {
        val tree = ReceiveFolder.tree(context)
        return try {
            // A chosen folder that cannot be written to is a failure, never a quiet move to another folder.
            if (tree != null) createInTree(tree, name) else createInDownloads(name)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun existsInTree(tree: Uri, name: String): Boolean {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0).equals(name, ignoreCase = true)) return true
            }
        }
        return false
    }

    private fun createInTree(tree: Uri, name: String): IncomingFile? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val document = DocumentsContract.createDocument(resolver, parent, mimeOf(name), name) ?: return null
        val stream = try {
            resolver.openOutputStream(document, "w")
        } catch (e: IOException) {
            null
        }
        if (stream == null) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            return null
        }
        val stored = displayName(document) ?: name
        return object : IncomingFile {
            override val name: String = stored
            override val out: OutputStream = stream

            override fun finish() = stream.close()

            override fun discard() {
                runCatching { stream.close() }
                runCatching { DocumentsContract.deleteDocument(resolver, document) }
            }
        }
    }

    private fun existsInDownloads(name: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(name, ReceiveFolder.DEFAULT_PATH),
            null,
        )?.use { return it.moveToFirst() }
        return false
    }

    private fun createInDownloads(name: String): IncomingFile? {
        // Below Android 10 writing to the shared Download folder needs a storage permission droidtop does not ask for.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, ReceiveFolder.DEFAULT_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val stream = try {
            resolver.openOutputStream(item, "w")
        } catch (e: IOException) {
            null
        }
        if (stream == null) {
            runCatching { resolver.delete(item, null, null) }
            return null
        }
        val stored = displayName(item) ?: name
        return object : IncomingFile {
            override val name: String = stored
            override val out: OutputStream = stream

            override fun finish() {
                stream.close()
                resolver.update(item, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }

            override fun discard() {
                runCatching { stream.close() }
                runCatching { resolver.delete(item, null, null) }
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    companion object {
        /**
         * A file the person picked on the console, to offer to the phone: its name and size as its provider says, read
         * when it is downloaded, never copied. Null for a URI droidtop's own providers issue (an app must not be able to
         * make droidtop hand out its own files) and for one that is not a `content:` URI. Off the main thread.
         */
        fun offered(context: Context, uri: Uri): OfferedFile? {
            val own = uri.authority?.startsWith(context.packageName) == true
            if (uri.scheme != "content" || own) return null
            val resolver = context.contentResolver
            var name: String? = null
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) {
                        name = it.getString(0)
                        if (!it.isNull(1)) size = it.getLong(1)
                    }
                }
            }
            return OfferedFile(name ?: uri.lastPathSegment ?: FileNames.FALLBACK, size) {
                resolver.openInputStream(uri) ?: throw IOException("The provider gave no stream")
            }
        }
    }
}
