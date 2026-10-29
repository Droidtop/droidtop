package dev.droidtop.library

import android.content.Context
import android.os.Environment
import java.io.File
import java.security.MessageDigest

/**
 * Screenshots filed under the game they were taken in (docs/SPEC.md "The companion's tabs", Screenshots,
 * Droidtop/tracker#596), as Steam's screenshot manager files them: while droidtop knows the running game
 * ([LaunchDisplay.runningGame]), a shot is also kept in droidtop's own folder for that game, and the game page's
 * media viewer lists them first, newest first. The copy in Pictures/Screenshots stays. Nothing is written into the
 * person's games folders, and the scraper's own screenshot (ES-DE's `screenshots`) is never replaced.
 */
object GameScreenshots {
    /** What the media viewer calls one. */
    const val LABEL = "Your screenshot"

    /** The folder [gameId]'s shots are kept in: droidtop's own pictures, one folder per game. Null without storage. */
    fun folderFor(context: Context, gameId: String): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(File(it, "games"), keyOf(gameId)) }

    /** A game id as a folder name: ids are paths and store keys, so the folder is named by a digest of the id. Pure. */
    fun keyOf(gameId: String): String =
        MessageDigest.getInstance("SHA-256").digest(gameId.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** Keeps [bytes] as [name] under the running game; whether it was, false when no game is running. Disk work. */
    fun save(context: Context, name: String, bytes: ByteArray): Boolean {
        val game = LaunchDisplay.runningGame ?: return false
        val folder = folderFor(context, game.gameId) ?: return false
        return runCatching {
            folder.mkdirs()
            File(folder, name).writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    /** [gameId]'s shots, newest first, labelled for the media viewer. Disk work. */
    fun of(context: Context, gameId: String): List<Pair<String, String>> =
        folderFor(context, gameId)?.listFiles { file -> file.isFile && file.extension.equals("png", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { LABEL to it.absolutePath }
            .orEmpty()
}
