package dev.droidtop.stores.steam

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What the person chose for one Steam game's content (docs/SPEC.md 7g,
 * "Stores", Droidtop/tracker#313): the branch it follows and the owned DLC
 * turned off. DLC is kept as the ones left out, so a DLC bought later is on
 * by default, which is what an install did before there was a picker.
 */
@Serializable
data class SteamChoice(
    val branch: String = SteamDownload.BRANCH,
    val excludedDlc: Set<Int> = emptySet(),
    /** Passwords Steam accepted for locked branches of this game, by branch name. */
    val branchPasswords: Map<String, String> = emptyMap(),
) {
    /** The password of the chosen branch, when it has one. */
    val password: String? get() = branchPasswords[branch]?.takeIf { it.isNotBlank() }
}

/**
 * The choices of every game, one small file in droidtop's own folder. Read
 * and written off the main thread (installs, the picker); a file that cannot
 * be read is an empty set of choices, which is the default for every game.
 */
internal object SteamChoices {
    private val JSON = Json { ignoreUnknownKeys = true }
    private val SERIALIZER = MapSerializer(Int.serializer(), SteamChoice.serializer())

    fun file(context: Context): File = File(context.filesDir, "steam/install_choices.json")

    fun encode(all: Map<Int, SteamChoice>): String = JSON.encodeToString(SERIALIZER, all)

    fun decode(text: String): Map<Int, SteamChoice> =
        runCatching { JSON.decodeFromString(SERIALIZER, text) }.getOrDefault(emptyMap())

    @Synchronized
    fun get(context: Context, appId: Int): SteamChoice = readAll(context)[appId] ?: SteamChoice()

    @Synchronized
    fun put(context: Context, appId: Int, choice: SteamChoice) {
        val all = readAll(context).toMutableMap()
        // The default needs no row.
        if (choice == SteamChoice()) all.remove(appId) else all[appId] = choice
        val file = file(context)
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(encode(all))
        check(temp.renameTo(file)) { "Could not save the content choice" }
    }

    private fun readAll(context: Context): Map<Int, SteamChoice> =
        file(context).takeIf { it.isFile }?.let { runCatching { decode(it.readText()) }.getOrNull() }.orEmpty()
}
