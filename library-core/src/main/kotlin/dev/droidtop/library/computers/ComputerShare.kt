package dev.droidtop.library.computers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.droidtop.net.peer.AgentNative
import dev.droidtop.net.peer.Computer
import dev.droidtop.net.peer.Computers
import dev.droidtop.net.peer.DeviceIdentity
import org.json.JSONObject
import java.io.File

/**
 * The person's own cloud folder as store and forward with their computers
 * (docs/SPEC.md 7o "Transports"; droidtop-agent docs/DESIGN.md section 10,
 * transport 3): a folder their own sync tool (Drive, OneDrive, Dropbox,
 * Nextcloud, Syncthing-Fork, FolderSync) carries between this device and the
 * computer, picked with the system's folder picker and named on the computer
 * with `droidtop-agent share set`. It is used only when the computer does not
 * answer.
 *
 * Each side leaves sealed letters for the other in
 * `droidtop-agent/<recipient id>/inbox/`. The agent core seals, opens and
 * applies them; it cannot open a picked folder, so this device keeps two
 * folders of its own with the same layout under `files/agent/share/`:
 * `in`, filled from the cloud folder before a call, and `out`, which the core
 * writes and this object empties into the cloud folder after it. A letter is
 * deleted from the cloud folder only once the core has opened it.
 */
object ComputerShare {
    private const val PREFS = "droidtop_agent_share"
    private const val KEY_TREE = "tree"
    private const val TOP = "droidtop-agent"
    private const val EXT = ".dtmsg"

    /** The picked folder, or null when none is set. */
    fun folder(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)

    /** Keeps [tree] (from the folder picker) with the permission to read and write it across restarts. */
    fun set(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        folder(context)?.takeIf { it != tree }?.let { old -> runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, tree.toString()).apply()
    }

    fun clear(context: Context) {
        folder(context)?.let { old -> runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE).apply()
    }

    /** The folder's name as the picker showed it, for the settings row. Asks the provider: never on the main thread. */
    fun label(context: Context): String? {
        val tree = folder(context) ?: return null
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return runCatching {
            context.contentResolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: tree.lastPathSegment
    }

    private fun local(context: Context): File = File(Computers.folder(context), "share")
    private fun inbox(context: Context): File = File(local(context), "in")
    private fun outbox(context: Context): File = File(local(context), "out")

    /**
     * The library through the cloud folder with [computer]: what it left is
     * applied, and what it has not had is left for it ([args] as for
     * `sync_library`). Null when no folder is set. Disk and provider work:
     * never on the main thread.
     */
    fun library(context: Context, computer: Computer, args: JSONObject): JSONObject? =
        exchange(context, computer, "share_library", args, fetch = true)

    /** A game's saves left in the cloud folder for [computer] ([args] as for `sync_saves`). Null when no folder is set. */
    fun postSaves(context: Context, computer: Computer, args: JSONObject): JSONObject? =
        exchange(context, computer, "share_post_saves", args, fetch = false)

    private fun exchange(context: Context, computer: Computer, op: String, args: JSONObject, fetch: Boolean): JSONObject? {
        val tree = folder(context) ?: return null
        val me = DeviceIdentity.id(context) ?: return JSONObject().put("error", "this device has no key yet; pair a computer first")
        val folder = runCatching { Folder(context, tree) }.getOrElse {
            return JSONObject().put("error", "the cloud folder cannot be opened (${it.message ?: it.javaClass.simpleName}); choose it again")
        }
        if (fetch) runCatching { fetch(context, folder, me) }.onFailure { return JSONObject().put("error", "the cloud folder could not be read (${it.message})") }
        args.put("inbox", inbox(context).absolutePath).put("outbox", outbox(context).absolutePath)
        val reply = Computers.call(context, computer, op, args)
        if (AgentNative.failure(reply) == null) {
            reply.optJSONArray("done")?.let { done ->
                val dir = folder.find(listOf(TOP, me, "inbox"))
                if (dir != null) (0 until done.length()).forEach { i -> folder.delete(dir, done.optString(i)) }
            }
        }
        // Letters left by an earlier call that could not reach the folder go too.
        val unsent = runCatching { send(context, folder) }.exceptionOrNull()
        if (unsent != null && AgentNative.failure(reply) == null) reply.put("unsent", unsent.message ?: unsent.javaClass.simpleName)
        return reply
    }

    /** Mirrors the letters the cloud folder holds for this device into [inbox]. */
    private fun fetch(context: Context, folder: Folder, me: String) {
        val target = File(inbox(context), "$TOP/$me/inbox")
        target.deleteRecursively()
        target.mkdirs()
        val dir = folder.find(listOf(TOP, me, "inbox")) ?: return
        folder.children(dir).filter { it.second.endsWith(EXT) && !it.third }.forEach { (id, name, _) ->
            val tmp = File(target, ".$name.part")
            folder.open(id).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(File(target, name))
        }
    }

    /** Moves every letter in [outbox] into the cloud folder at the same place, oldest first. */
    private fun send(context: Context, folder: Folder) {
        val root = outbox(context)
        root.walkTopDown().filter { it.isFile && it.name.endsWith(EXT) }.sortedBy { it.name }.toList().forEach { file ->
            val parts = file.relativeTo(root).invariantSeparatorsPath.split('/')
            val dir = folder.ensure(parts.dropLast(1))
            folder.write(dir, file)
            file.delete()
        }
    }

    /** A picked folder, through the document provider that serves it. */
    private class Folder(context: Context, private val tree: Uri) {
        private val resolver = context.contentResolver
        private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

        private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

        /** (document id, name, is a folder) of [parent]'s children. */
        fun children(parent: String): List<Triple<String, String, Boolean>> {
            val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            return resolver.query(query, projection, null, null, null)?.use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2) == Document.MIME_TYPE_DIR)) }
            }.orEmpty()
        }

        fun find(path: List<String>): String? = path.fold<String, String?>(rootId) { at, name ->
            at?.let { id -> children(id).firstOrNull { it.second == name && it.third }?.first }
        }

        fun ensure(path: List<String>): String = path.fold(rootId) { at, name ->
            children(at).firstOrNull { it.second == name && it.third }?.first
                ?: DocumentsContract.createDocument(resolver, uri(at), Document.MIME_TYPE_DIR, name)?.let(DocumentsContract::getDocumentId)
                ?: error("could not make the folder $name")
        }

        fun open(id: String) = resolver.openInputStream(uri(id)) ?: error("could not read a letter")

        /**
         * Writes [file] into [dir] under its own name: under a temporary name
         * first and renamed, so the computer's sync tool never carries half a
         * letter, or directly where the provider cannot rename.
         */
        fun write(dir: String, file: File) {
            val part = ".${file.name}.part"
            val made = DocumentsContract.createDocument(resolver, uri(dir), "application/octet-stream", part) ?: error("could not write to the cloud folder")
            resolver.openOutputStream(made, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("could not write to the cloud folder")
            val renamed = runCatching { DocumentsContract.renameDocument(resolver, made, file.name) }.getOrNull()
            if (renamed == null) {
                runCatching { DocumentsContract.deleteDocument(resolver, made) }
                val direct = DocumentsContract.createDocument(resolver, uri(dir), "application/octet-stream", file.name) ?: error("could not write to the cloud folder")
                resolver.openOutputStream(direct, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("could not write to the cloud folder")
            }
        }

        fun delete(dir: String, name: String) {
            children(dir).filter { it.second == name && !it.third }.forEach { runCatching { DocumentsContract.deleteDocument(resolver, uri(it.first)) } }
        }
    }
}
