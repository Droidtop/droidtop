package dev.droidtop.library

import android.content.Context
import java.io.File

/**
 * Install on a GOG installer's row (docs/SPEC.md 7g, "Installers in a games folder", Droidtop/tracker#405): the
 * game is taken out of the installer without running it, and Wine runs it only when that cannot be done, through
 * the one door "Install a Windows game" uses (`WindowsInstalls.install`). The installer is read where it is, never
 * copied, moved or changed. The new game goes in droidtop's own storage, in a folder named [FolderInstaller.title],
 * so it folds onto the installer's card.
 */
object FolderInstalls {
    /**
     * Installs from an installer file, naming the new game's folder: set once by the app, where the Windows side
     * lives. Returns the line to show.
     */
    @Volatile
    var runner: (suspend (context: Context, installer: File, title: String, onStatus: (String) -> Unit) -> String)? = null

    /** Installs the game [entry]'s installer holds; returns the line to show. [onStatus] carries progress. */
    suspend fun install(context: Context, entry: LibraryEntry, onStatus: (String) -> Unit): String {
        val installer = entry.pcInfo?.installer ?: return "${entry.title} is not an installer"
        val folder = entry.pcInfo.installPath?.takeIf { it.isNotBlank() } ?: return "${installer.file} has no folder on this device"
        val run = runner ?: return "This build cannot install from an installer"
        return run(context, File(folder, installer.file), installer.title, onStatus)
    }
}
