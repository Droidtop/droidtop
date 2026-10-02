package dev.droidtop.runtime.windows

import android.content.Context
import android.system.Os
import app.gamenative.utils.CustomGameIdStore
import app.gamenative.utils.CustomGameIdStores
import dev.droidtop.library.GameFolderIds
import java.io.File

/**
 * droidtop's id store for the vendored gamenative scanner: the id of a
 * PC game folder lives in droidtop's own storage, never in a
 * `.gamenative` file inside the user's game folder (docs/SPEC.md 7g,
 * tracker#269). gamenative-tux as its own app keeps the file; this is
 * the hook the fork gives an embedding host.
 *
 * A `.gamenative` file that is already there is read as legacy identity
 * and adopted ([GameFolderIds]); it is not written and not deleted.
 */
object DroidtopGameIdStore : CustomGameIdStore {
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
            CustomGameIdStores.install(this)
        }
    }

    override fun read(folder: File): Int? = ids?.idFor(folder)

    override fun write(folder: File, gameId: Int) {
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
