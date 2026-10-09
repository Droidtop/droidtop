package dev.droidtop.app.settings

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * A file the system picker handed back, as the real path on this device that Wine can start. A document
 * URI is a handle to a file, and a Windows program needs the file itself in place: where it is, beside
 * its own data, never a copy (docs/SPEC.md 7c, "Prefix tools"). Only the pickers of the shared storage
 * (a volume, or Downloads) name a path; a document from any other provider has none and comes back null.
 * Disk work: the caller is off the main thread.
 */
internal object PickedFiles {

    fun fileOf(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.isFile }
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val path = pathOf(uri.authority, documentId, Environment.getExternalStorageDirectory().path) ?: return null
        return File(path).takeIf { it.isFile }
    }

    /**
     * The path a document id names. [primaryRoot] is the primary volume's mount. Null for a provider
     * that does not name a path.
     */
    internal fun pathOf(authority: String?, documentId: String, primaryRoot: String): String? = when (authority) {
        "com.android.externalstorage.documents" -> {
            val volume = documentId.substringBefore(':', "")
            val relative = documentId.substringAfter(':', "")
            when {
                volume.isEmpty() || relative.isEmpty() -> null
                volume.equals("primary", ignoreCase = true) -> "$primaryRoot/$relative"
                else -> "/storage/$volume/$relative"
            }
        }
        "com.android.providers.downloads.documents" -> documentId.takeIf { it.startsWith("raw:/") }?.removePrefix("raw:")
        else -> null
    }
}
