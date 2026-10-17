package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.library.consoles.RomDatabase

/**
 * Hide a game from droidtop's lists, or show it again: the one Hidden rule every list follows, the `hidden` field
 * of the game's `game_metadata` row (docs/SPEC.md 7j), which the next read of the library applies to the entry.
 * Games of every kind keep it, keyed by the entry's id; an app is hidden through [AppHiding] instead. A row is
 * made for a game that has none. Database work: call it off the main thread.
 */
object GameHiding {
    suspend fun set(context: Context, id: String, hidden: Boolean) {
        val dao = RomDatabase.get(context).romDao()
        val current = dao.getGameMetadataSingle(id) ?: GameMetadataEntity(id = id)
        if (current.hidden != hidden) dao.upsertGameMetadata(current.copy(hidden = hidden))
    }
}
