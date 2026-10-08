package dev.droidtop.runtime.windows

import android.content.Context
import android.system.Os
import dev.droidtop.library.GameFolderIds
import java.io.File

/**
 * Where the folder scanner ([dev.droidtop.runtime.windows.utils.CustomGameScanner])
 * keeps a PC game folder's id: droidtop's own storage, never a
 * `.gamenative` file inside the user's game folder (docs/SPEC.md 7g,
 * tracker#269).
 *
 * A `.gamenative` file that is already there is read as legacy identity
 * and adopted ([GameFolderIds]); it is not written and not deleted.
 */
object DroidtopGameIdStore {
    private val APP_ID = Regex("${'"'}appId${'"'}[ ]*:[ ]*([0-9]+)")

    @Volatile
    private var ids: GameFolderIds? = null

    /** Idempotent; called before anything can reach the scanner. */
    fun install(context: Context) {
        if (ids != null) return
        synchronized(this) {
            if (ids != null) return
            ids = GameFolderIds(
                store = File(context.applicationContext.filesDir, "pc-game-ids.tsv"),
                legacyRead = ::readLegacyFile,
                stat = ::statOf,
            )
            dev.droidtop.runtime.windows.utils.CustomGameCache.invalidate()
        }
    }

    fun read(folder: File): Int? = ids?.idFor(folder)

    fun write(folder: File, gameId: Int) {
        ids?.remember(folder, gameId)
    }

    private fun statOf(folder: File): String? = runCatching {
        val st = Os.stat(folder.absolutePath)
        if (st.st_ino == 0L) null else "${st.st_dev}:${st.st_ino}"
    }.getOrNull()

    /** Read only: gamenative's own reader rewrites a plain-number file, this never does. */
    private fun readLegacyFile(folder: File): Int? = runCatching {
        val file = File(folder, ".gamenative")
        val text = if (file.isFile) file.readText().trim() else ""
        val id = text.toIntOrNull() ?: APP_ID.find(text)?.groupValues?.get(1)?.toIntOrNull()
        id?.takeIf { it > 0 }
    }.getOrNull()
}
