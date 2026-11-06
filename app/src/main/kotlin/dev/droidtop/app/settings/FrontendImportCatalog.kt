package dev.droidtop.app.settings

import android.content.Context
import android.net.Uri
import dev.droidtop.app.LibraryCore
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.imports.PegasusImport
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.TextBlockItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > Library > Import from another frontend (Droidtop/tracker#724): what a person kept in another frontend,
 * brought into droidtop's favourites and collections, matched against the games droidtop already has. Only read: no
 * ROM is touched, moved or copied, and the other frontend's files are never written. Each import says how many of
 * its games were found. Slice 1: Pegasus (its collections and its favourites); Daijisho and LaunchBox follow.
 */
object FrontendImportCatalog {
    const val SCREEN_ID = "frontend_import"

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Import from another frontend",
        subtitle = "Favourites and collections, matched to your games; your ROMs are not touched",
        groups = { _ ->
            listOf(
                CatalogGroup(
                    id = "frontend_import_pegasus",
                    title = "Pegasus",
                    items = listOf(
                        DocumentPickItem(
                            id = "frontend_import_pegasus_metadata",
                            title = "Collections from metadata.pegasus.txt",
                            subtitle = "Each Pegasus collection becomes a collection here, with the games found",
                            // A .txt is often labelled octet-stream by file managers; the file is parsed before use.
                            mimeType = "*/*",
                            onPicked = { ctx, uri -> importPegasusCollections(ctx, uri) },
                        ),
                        DocumentPickItem(
                            id = "frontend_import_pegasus_favorites",
                            title = "Favourites from favorites.txt",
                            subtitle = "In Pegasus's own data folder; the games found become favourites here",
                            mimeType = "*/*",
                            onPicked = { ctx, uri -> importPegasusFavorites(ctx, uri) },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "frontend_import_launchbox",
                    title = "LaunchBox",
                    items = listOf(
                        DocumentPickItem(
                            id = "frontend_import_launchbox_platform",
                            title = "Favourites and play time",
                            subtitle = "From a platform file, Data/Platforms/<platform>.xml; one platform at a time",
                            mimeType = "*/*",
                            onPicked = { ctx, uri -> importLaunchBoxPlatform(ctx, uri) },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "frontend_import_note",
                    title = null,
                    items = listOf(
                        TextBlockItem(
                            id = "frontend_import_note_text",
                            text = "A game is found by its file: the same path, the same path inside a games folder, or " +
                                "the only game with that file name. Games droidtop has not found yet are listed as not " +
                                "in your library; scan your game folders first.",
                        ),
                    ),
                ),
            )
        },
    )

    private suspend fun readText(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
    }

    private suspend fun roms(context: Context): List<LibraryEntry> =
        LibraryCore.library(context).currentEntriesOf(setOf(LibraryEntryKind.CONSOLE_ROM))

    private suspend fun importPegasusCollections(context: Context, uri: Uri): String {
        val text = readText(context, uri) ?: return "That file could not be read"
        val collections = PegasusImport.parseMetadata(text)
        if (collections.isEmpty()) return "No Pegasus collection in that file"
        val library = LibraryCore.library(context)
        val entries = roms(context)
        val byId = entries.associateBy { it.id }
        val existing = library.getCollections().associateBy { it.name }
        var found = 0
        var total = 0
        for (collection in collections) {
            val match = PegasusImport.match(collection.files, entries.map { it.id })
            total += collection.files.size
            found += match.ids.size
            if (match.ids.isEmpty()) continue
            val target = existing[collection.name] ?: library.createCollection(collection.name) ?: continue
            for (id in match.ids) {
                val entry = byId[id] ?: continue
                if (!library.isCollectionMember(target.id, entry)) library.toggleCollectionMembership(target.id, entry)
            }
        }
        return PegasusImport.reportLine(found, total, "${collections.size} collections")
    }

    /** Which LaunchBox games' play time was already brought in, so a second import adds none twice. */
    private const val KEY_LAUNCHBOX_DONE = "droidtop_import_launchbox_played"

    private suspend fun importLaunchBoxPlatform(context: Context, uri: Uri): String {
        val text = readText(context, uri) ?: return "That file could not be read"
        val games = dev.droidtop.library.imports.LaunchBoxImport.parse(text)
        if (games.isEmpty()) return "No LaunchBox game in that file"
        val library = LibraryCore.library(context)
        val entries = roms(context)
        val byId = entries.associateBy { it.id }
        val paths = entries.map { it.id }
        val prefs = dev.droidtop.library.settings.CatalogPrefs.prefs(context)
        val done = withContext(Dispatchers.IO) { prefs.getStringSet(KEY_LAUNCHBOX_DONE, emptySet()).orEmpty().toMutableSet() }
        var found = 0
        var timed = 0
        for (game in games) {
            val entry = PegasusImport.match(listOf(game.matchPath), paths).ids.firstOrNull()?.let(byId::get) ?: continue
            found++
            if (game.favorite && !entry.favorite) library.toggleFavorite(entry)
            // Play time as one session in "Your play", ending when LaunchBox last saw it played; once per game.
            val key = game.id.ifEmpty { game.path }
            if (game.playSeconds > 0 && key !in done) {
                val end = dev.droidtop.library.imports.LaunchBoxImport.lastPlayedMs(game) ?: System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    dev.droidtop.library.settings.PlayLogStore.record(context, entry.id, entry.title, entry.systemId, end - game.playSeconds * 1000L, end)
                }
                done += key
                timed++
            }
        }
        withContext(Dispatchers.IO) { prefs.edit().putStringSet(KEY_LAUNCHBOX_DONE, done).apply() }
        return PegasusImport.reportLine(found, games.size, "LaunchBox") + if (timed > 0) "; play time added for $timed" else ""
    }

    private suspend fun importPegasusFavorites(context: Context, uri: Uri): String {
        val text = readText(context, uri) ?: return "That file could not be read"
        val files = PegasusImport.parseFavorites(text)
        if (files.isEmpty()) return "No favourite in that file"
        val library = LibraryCore.library(context)
        val entries = roms(context)
        val byId = entries.associateBy { it.id }
        val match = PegasusImport.match(files, entries.map { it.id })
        for (id in match.ids) {
            val entry = byId[id] ?: continue
            if (!entry.favorite) library.toggleFavorite(entry)
        }
        return PegasusImport.reportLine(match.ids.size, files.size, "Favourites")
    }
}
