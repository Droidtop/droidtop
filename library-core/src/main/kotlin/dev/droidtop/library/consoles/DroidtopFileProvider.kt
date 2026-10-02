package dev.droidtop.library.consoles

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * droidtop's one content provider for handing a game file to another app
 * (the `{file.uri}` launch placeholder, docs/SPEC.md "Launch intents and file
 * access"). It is androidx's [FileProvider], so URI issuing, grants and
 * `openFile` are unchanged; the one difference is the query answer.
 *
 * androidx answers only `_display_name` and `_size`, in that order, whatever
 * columns were asked for. An emulator that stats the URI with a larger
 * projection (NetherSX2's `FileHelper.statFile` reads past column 1: "Failed
 * to read row 0, column 1 from a window with 1 rows, 1 columns", rig,
 * Droidtop/tracker#270) got a one-column cursor, threw, and never booted. This
 * answers the whole document column set ([FileDocumentColumns.ALL_COLUMNS]) and exactly the
 * columns asked for, in the order asked, with null for one it does not know,
 * the way a documents provider does.
 */
class DroidtopFileProvider : FileProvider() {
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        // The super call is the validation: it resolves the URI against
        // file_paths.xml and throws for one that is not droidtop's to serve.
        val columns = projection?.toList()?.toTypedArray() ?: FileDocumentColumns.ALL_COLUMNS
        val base = super.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        val identity = base.use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null }
            ?: return MatrixCursor(columns, 0)
        val (name, size) = identity
        val path = FileDocumentColumns.filePathOf(uri.path)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(
            Array(columns.size) { i ->
                FileDocumentColumns.columnValue(
                    columns[i],
                    name = name,
                    size = size,
                    lastModified = path?.let { File(it).lastModified() } ?: 0L,
                    mimeType = getType(uri) ?: "application/octet-stream",
                    documentId = path ?: name,
                )
            },
        )
        return cursor
    }
}

/** The column set droidtop's provider answers and how each cell is filled; pure, so it is tested without a device. */
internal object FileDocumentColumns {
    /** What a null projection returns: the openable columns, then the document ones a stat reads. */
    val ALL_COLUMNS: Array<String> = arrayOf(
        OpenableColumns.DISPLAY_NAME,
        OpenableColumns.SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    )

    /** One cell of the answer; null for a column this provider has no value for. */
    internal fun columnValue(
        column: String,
        name: String,
        size: Long,
        lastModified: Long,
        mimeType: String,
        documentId: String,
    ): Any? = when (column) {
        OpenableColumns.DISPLAY_NAME -> name
        OpenableColumns.SIZE -> size
        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> lastModified
        DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeType
        // A plain read-only file: no create, delete, rename or thumbnail flags.
        DocumentsContract.Document.COLUMN_FLAGS -> 0
        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
        else -> null
    }

    /**
     * The file a URI path names under `file_paths.xml`'s `root-path` entry
     * (`/root/<absolute path without its leading slash>`), or null for a
     * path outside it.
     */
    internal fun filePathOf(uriPath: String?): String? =
        uriPath?.takeIf { it.startsWith("/root/") }?.let { "/" + it.removePrefix("/root/") }
}
