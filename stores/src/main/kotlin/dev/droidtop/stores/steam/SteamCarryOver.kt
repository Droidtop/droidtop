package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.stores.StoreChanges
import dev.droidtop.stores.util.Marker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Brings a Steam sign-in and Steam installs made through GameNative across
 * to droidtop's own Steam (docs/SPEC.md 7g, "Stores"), once. The rows came
 * across with the database itself (SteamDatabase's import); what a row
 * cannot carry is handed in here by the app, which reads it from
 * GameNative's preferences while the Windows runtime still compiles
 * GameNative in: the account name and refresh token GameNative kept, and
 * the folders GameNative installed Steam games under.
 *
 * Nothing is moved: an install GameNative made stays in its folder, and
 * droidtop records that folder as the game's ([AppInfo.installPath]). New
 * installs go to the person's game folders.
 */
object SteamCarryOver {
    /** The sign-in GameNative kept, in its own words. */
    data class Session(val accountName: String, val refreshToken: String, val steamId64: Long, val clientId: Long?, val cellId: Int)

    private const val PREFS = "steam_carry_over"
    private const val DONE = "done"

    /** Whether the carry-over has already run on this device. */
    fun done(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DONE, false)

    /**
     * Takes [session] when droidtop holds no Steam sign-in of its own, and
     * records where each install GameNative listed is, looking under
     * [installRoots] (GameNative's Steam install folders) by the game's
     * folder name. Runs once; off the main thread. Returns how many installs
     * were found.
     */
    suspend fun bringAcross(context: Context, session: Session?, installRoots: List<File>): Int = withContext(Dispatchers.IO) {
        if (done(context)) return@withContext 0
        if (session != null && session.accountName.isNotBlank() && session.refreshToken.isNotBlank() && !SteamCredentials.exists(context)) {
            SteamCredentials.save(
                context,
                SteamCredentials(
                    accountName = session.accountName,
                    refreshToken = session.refreshToken,
                    steamId64 = session.steamId64,
                    clientId = session.clientId,
                    cellId = session.cellId,
                ),
            )
            Timber.tag(TAG).i("Brought the Steam sign-in across from GameNative")
        }
        val db = SteamDatabase.get(context)
        var found = 0
        for (install in db.installs().all()) {
            if (!install.isDownloaded || install.installPath.isNotBlank()) continue
            val app = db.apps().find(install.id)
            val names = listOfNotNull(app?.folderName, app?.name)
            val dir = SteamInstalls.findInstall(install.customInstallPath, installRoots, names) { File(it, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).exists() }
            if (dir != null) {
                db.installs().upsert(install.copy(installPath = dir.absolutePath))
                found++
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DONE, true).apply()
        if (found > 0 || session != null) StoreChanges.announce(context)
        Timber.tag(TAG).i("Found $found Steam installs GameNative made")
        found
    }

    private const val TAG = "SteamCarryOver"
}
