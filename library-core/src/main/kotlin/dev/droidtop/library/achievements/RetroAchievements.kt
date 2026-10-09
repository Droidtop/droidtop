package dev.droidtop.library.achievements

import android.content.Context
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.achievements.hash.RaConsole
import dev.droidtop.library.achievements.hash.RaConsoles
import dev.droidtop.library.achievements.hash.RaHashKind
import dev.droidtop.library.achievements.hash.RaHasher
import dev.droidtop.library.credentials.CredentialStore
import dev.droidtop.library.scraper.ScrapeLookup
import java.io.File
import java.io.IOException

/** What asking RetroAchievements about one game came to. */
sealed interface RaResult {
    /** No username and Web API key are stored: nothing is asked. */
    data object NotSignedIn : RaResult

    /** RetroAchievements has no console for this game's system. */
    data object Unsupported : RaResult

    /** The game is not one RetroAchievements has achievements for ([why] says how that was found out). */
    data class NotFound(val why: String) : RaResult

    /** The request failed; [message] is one sentence for the person. */
    data class Failed(val message: String) : RaResult

    /**
     * The game and the person's progress in it. [matchedBy] is how it was recognised: by "file hash" (the
     * file is exactly a set RetroAchievements lists) or "name" (a title that matches exactly, no more).
     * [stale] when the progress shown is older than its time to live because the service could not be reached.
     */
    data class Known(val game: RaGame, val matchedBy: String, val progress: RaProgress?, val stale: Boolean) : RaResult
}

/**
 * RetroAchievements for one library game (docs/SPEC.md 7h, "RetroAchievements"): which RetroAchievements game
 * a file is, then the person's progress in it. Blocking and doing file and network work: called off the main
 * thread, when a game's own page opens and never for a list. What is learned is cached under the cache directory:
 * a console's list of games and hashes for a week (one request per console), a file's hash by path, size and
 * modified time, a game's progress for ten minutes.
 */
object RetroAchievements {

    private const val LIST_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val PROGRESS_TTL_MS = 10L * 60 * 1000

    fun isConfigured(context: Context): Boolean =
        CredentialStore.get(context, CredentialStore.RETROACHIEVEMENTS_USER).isNotBlank() &&
            CredentialStore.get(context, CredentialStore.RETROACHIEVEMENTS_API_KEY).isNotBlank()

    fun lookup(context: Context, entry: LibraryEntry, now: Long = System.currentTimeMillis()): RaResult {
        val console = RaConsoles.forSystem(entry.systemId) ?: return RaResult.Unsupported
        val user = CredentialStore.get(context, CredentialStore.RETROACHIEVEMENTS_USER)
        val key = CredentialStore.get(context, CredentialStore.RETROACHIEVEMENTS_API_KEY)
        if (user.isBlank() || key.isBlank()) return RaResult.NotSignedIn
        val directory = File(context.cacheDir, "retroachievements").also { it.mkdirs() }
        return try {
            val games = when (val list = gameList(directory, key, console.id, now)) {
                is ScrapeLookup.Found -> list.value
                is ScrapeLookup.Refused -> return RaResult.Failed(refusal(list))
                ScrapeLookup.NoMatch -> emptyList()
            }
            val identified = identify(directory, entry, console, games)
                ?: return RaResult.NotFound("RetroAchievements lists no achievements for this game")
            val outcome = progress(directory, user, key, identified.first, now)
            when (outcome) {
                is Progress.Fresh -> RaResult.Known(identified.first, identified.second, outcome.value, stale = false)
                is Progress.Stale -> RaResult.Known(identified.first, identified.second, outcome.value, stale = true)
                is Progress.Refused -> RaResult.Failed(refusal(outcome.refusal))
                Progress.Unavailable -> RaResult.Known(identified.first, identified.second, null, stale = false)
            }
        } catch (e: IOException) {
            RaResult.Failed("No connection to RetroAchievements")
        }
    }

    private fun refusal(refused: ScrapeLookup.Refused): String =
        if (refused.httpStatus == 401 || refused.httpStatus == 403) {
            "RetroAchievements refused your username or key: check them under Settings > Accounts and sources"
        } else {
            "RetroAchievements answered HTTP ${refused.httpStatus}"
        }

    // ----- the console's games ---------------------------------------------

    private fun gameList(directory: File, key: String, consoleId: Int, now: Long): ScrapeLookup<List<RaGame>> {
        val cached = File(directory, "games-$consoleId.json")
        val fresh = cached.isFile && now - cached.lastModified() < LIST_TTL_MS
        if (fresh) RetroAchievementsClient.parseGameList(cached.readText()).takeIf { it.isNotEmpty() }?.let { return ScrapeLookup.Found(it) }
        return try {
            when (val answer = RetroAchievementsClient.gameList(key, consoleId)) {
                is ScrapeLookup.Found -> {
                    writeAtomically(cached, RaCache.serializeGames(answer.value))
                    answer
                }
                else -> answer
            }
        } catch (e: IOException) {
            // Offline: a list older than its time to live still identifies the game.
            val old = if (cached.isFile) RetroAchievementsClient.parseGameList(cached.readText()) else emptyList()
            if (old.isEmpty()) throw e
            ScrapeLookup.Found(old)
        }
    }

    // ----- which game ------------------------------------------------------

    private fun identify(directory: File, entry: LibraryEntry, console: RaConsole, games: List<RaGame>): Pair<RaGame, String>? {
        if (games.isEmpty()) return null
        if (console.kind != RaHashKind.NONE) {
            val file = File(entry.id)
            val hash = fileHash(directory, file, console)
            if (hash != null) {
                games.firstOrNull { hash in it.hashes }?.let { return it to "file hash" }
            }
        }
        val title = entry.gameName ?: GameNaming.displayName(entry.title)
        RaMatching.byName(games, title)?.let { return it to "name" }
        return null
    }

    /** The RetroAchievements hash of [file], from the cache while path, size and modified time are the same. */
    private fun fileHash(directory: File, file: File, console: RaConsole): String? {
        if (!file.isFile) return null
        val stamp = "${file.absolutePath}\t${file.length()}\t${file.lastModified()}\t${console.id}"
        val index = File(directory, "hashes.tsv")
        synchronized(hashLock) {
            val known = RaCache.readHashes(index)
            known[stamp]?.let { return it.ifEmpty { null } }
        }
        val hash = RaHasher.hash(file, console)
        synchronized(hashLock) {
            val known = RaCache.readHashes(index)
            known[stamp] = hash.orEmpty()
            writeAtomically(index, RaCache.serializeHashes(known))
        }
        return hash
    }

    private val hashLock = Any()

    // ----- progress ----------------------------------------------------------

    private sealed interface Progress {
        class Fresh(val value: RaProgress) : Progress
        class Stale(val value: RaProgress) : Progress
        class Refused(val refusal: ScrapeLookup.Refused) : Progress
        data object Unavailable : Progress
    }

    private fun progress(directory: File, user: String, key: String, game: RaGame, now: Long): Progress {
        val cached = File(directory, "progress-${game.id}.json")
        val old = if (cached.isFile) RetroAchievementsClient.parseProgress(cached.readText()) else null
        if (old != null && now - cached.lastModified() < PROGRESS_TTL_MS) return Progress.Fresh(old)
        return try {
            when (val answer = RetroAchievementsClient.progressText(user, key, game.id)) {
                is ScrapeLookup.Found -> {
                    val parsed = RetroAchievementsClient.parseProgress(answer.value)
                    if (parsed == null) {
                        old?.let { Progress.Stale(it) } ?: Progress.Unavailable
                    } else {
                        writeAtomically(cached, answer.value)
                        Progress.Fresh(parsed)
                    }
                }
                is ScrapeLookup.Refused -> Progress.Refused(answer)
                ScrapeLookup.NoMatch -> Progress.Unavailable
            }
        } catch (e: IOException) {
            old?.let { Progress.Stale(it) } ?: throw e
        }
    }

    private fun writeAtomically(target: File, text: String) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(target)) {
            target.writeText(text)
            temporary.delete()
        }
    }
}

/** Matching a library title to RetroAchievements' titles: exact after normalising, never a guess. */
internal object RaMatching {

    /** Lower case letters and digits only, without brackets and parentheses, "the" as a word, "&" as "and". */
    fun normalize(title: String): String {
        var text = title.lowercase()
        text = text.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|~[^~]*~"), " ")
        text = text.replace("&", " and ")
        text = text.replace(Regex("\\bthe\\b"), " ")
        return text.filter { it.isLetterOrDigit() }
    }

    /** The one game whose title normalises to the same as [title]; null when none does or several do. */
    fun byName(games: List<RaGame>, title: String): RaGame? {
        val wanted = normalize(title)
        if (wanted.isEmpty()) return null
        val matches = games.filter { normalize(it.title) == wanted }
        return matches.singleOrNull()
    }
}

/** The small files the cache keeps; plain text so a bad one is skipped, never fatal. */
internal object RaCache {

    fun serializeGames(games: List<RaGame>): String {
        val array = org.json.JSONArray()
        for (game in games) {
            array.put(
                org.json.JSONObject()
                    .put("ID", game.id)
                    .put("Title", game.title)
                    .put("ConsoleID", game.consoleId)
                    .put("NumAchievements", game.numAchievements)
                    .put("Points", game.points)
                    .put("Hashes", org.json.JSONArray(game.hashes)),
            )
        }
        return array.toString()
    }

    fun readHashes(file: File): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (!file.isFile) return map
        file.forEachLine { line ->
            val cut = line.lastIndexOf('\t')
            if (cut > 0) map[line.substring(0, cut)] = line.substring(cut + 1)
        }
        return map
    }

    fun serializeHashes(map: Map<String, String>): String {
        // Bounded: the newest 4000 files, which is more than a handheld's library of discs.
        val lines = map.entries.toList().takeLast(4000)
        return lines.joinToString("\n") { "${it.key}\t${it.value}" }
    }
}
