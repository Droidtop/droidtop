package dev.droidtop.shell.gamepad

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.FolderPickItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The system's folder and document pickers for a catalog's [FolderPickItem] and [DocumentPickItem], one for every
 * renderer: Gaming's Settings ([CatalogNavigator]) and the companion's catalog host (Droidtop/tracker#786, S2a2) open
 * them the same way. The renderer owns the picker, as those items promise: a folder's read permission is taken and
 * kept, and the item's own [FolderPickItem.onPicked] / [DocumentPickItem.onPicked] runs off the main thread.
 * [onStatus] gets the row's line while it works and what it said after (null when a folder pick succeeded with
 * nothing to say); [onDone] runs after, so the renderer reads its screen again.
 */
class CatalogPickers internal constructor(
    val pickFolder: (FolderPickItem) -> Unit,
    val pickDocument: (DocumentPickItem) -> Unit,
)

@Composable
fun rememberCatalogPickers(onStatus: (id: String, line: String?) -> Unit, onDone: () -> Unit): CatalogPickers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingFolder by remember { mutableStateOf<FolderPickItem?>(null) }
    var pendingDocument by remember { mutableStateOf<DocumentPickItem?>(null) }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val item = pendingDocument
        pendingDocument = null
        val uri = result.data?.data
        if (uri == null || item == null) return@rememberLauncherForActivityResult
        // onPicked reads or writes the document: never on the main thread.
        onStatus(item.id, WORKING)
        scope.launch {
            onStatus(item.id, withContext(Dispatchers.IO) { item.onPicked(context, uri) })
            onDone()
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val item = pendingFolder
        pendingFolder = null
        if (uri == null || item == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        onStatus(item.id, WORKING)
        scope.launch {
            val error = runCatching { item.onPicked(context, uri) }
                .getOrElse { "Couldn't share that folder: ${it.message ?: "an unknown error"}" }
            onStatus(item.id, error)
            onDone()
        }
    }
    return remember(documentLauncher, folderLauncher) {
        CatalogPickers(
            pickFolder = { item ->
                pendingFolder = item
                folderLauncher.launch(null)
            },
            pickDocument = { item ->
                pendingDocument = item
                documentLauncher.launch(item.pickerIntent())
            },
        )
    }
}

/** The row's line while a picked folder or document is being read or written. */
private const val WORKING = "Working..."
