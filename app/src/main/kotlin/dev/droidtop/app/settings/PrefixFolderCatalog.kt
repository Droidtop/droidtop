package dev.droidtop.app.settings

import android.text.format.Formatter
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.runtime.windows.PrefixFolderView
import dev.droidtop.runtime.windows.WinePrefixTools
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Wine prefix's files as a read-only view (docs/SPEC.md 7c, "Prefix tools"): folders to open, files with
 * their sizes, links named and not followed. Nothing in it changes, moves or deletes a file; it exists so a
 * person can see what a game left in the prefix (its saves under `drive_c/users`, an installer's folder)
 * before they reach for the registry editor or a reset. [PrefixFolderView] lists a folder.
 */
object PrefixFolderCatalog {

    /** The prefix [entryId] starts in (null: the shared one), from its top. */
    fun screen(entryId: String?, title: String?): CatalogScreen = CatalogScreen(
        id = "wine_prefix_folder",
        title = "Prefix files",
        subtitle = title,
        groups = { context ->
            val root = WinePrefixTools.folderOf(context, entryId)
            if (root == null) emptyNotice("There is no prefix to look at yet") else folderGroups(context, root, root)
        },
        indexGroups = { _ -> emptyList() },
    )

    private fun screenOf(root: File, folder: File): CatalogScreen {
        val relative = folder.path.removePrefix(root.path).trimStart('/')
        return CatalogScreen(
            id = "wine_prefix_folder:$relative",
            title = folder.name,
            subtitle = "Read only",
            groups = { context -> folderGroups(context, root, folder) },
            indexGroups = { _ -> emptyList() },
        )
    }

    private suspend fun folderGroups(context: android.content.Context, root: File, folder: File): List<CatalogGroup> {
        val listing = withContext(Dispatchers.IO) {
            if (PrefixFolderView.isInside(root, folder)) PrefixFolderView.list(folder) else null
        } ?: return emptyNotice("That folder is not inside the prefix")
        val here = ActionItem(
            id = "wine_prefix_folder_here",
            title = "Read only",
            subtitle = folder.path + if (folder == root) ". drive_c is C:" else "",
            run = {},
        )
        val rows = if (listing.entries.isEmpty()) {
            listOf<CatalogItem>(ActionItem(id = "wine_prefix_folder_empty", title = "Nothing in this folder", run = {}))
        } else {
            listing.entries.map { entry ->
                val id = "wine_prefix_entry:" + entry.name
                when {
                    entry.link -> ActionItem(id = id, title = entry.name, subtitle = "Link to ${entry.target ?: "somewhere"}; not opened here", value = "Link", run = {})
                    entry.directory -> NestedScreenItem(id = id, title = entry.name, inline = screenOf(root, File(folder, entry.name)))
                    else -> ActionItem(id = id, title = entry.name, value = Formatter.formatShortFileSize(context, entry.size), run = {})
                }
            } + if (listing.more > 0) {
                listOf(ActionItem(id = "wine_prefix_folder_more", title = "${listing.more} more not shown", run = {}))
            } else {
                emptyList()
            }
        }
        return listOf(CatalogGroup(id = "wine_prefix_folder_info", title = null, items = listOf(here)), CatalogGroup(id = "wine_prefix_folder_rows", title = null, items = rows))
    }

    private fun emptyNotice(text: String): List<CatalogGroup> =
        listOf(CatalogGroup(id = "wine_prefix_folder_none", title = null, items = listOf(ActionItem(id = "wine_prefix_folder_none_row", title = text, run = {}))))
}
