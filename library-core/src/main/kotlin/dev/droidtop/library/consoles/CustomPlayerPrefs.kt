package dev.droidtop.library.consoles

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * User-defined [Player.AmStart] entries, one list per console system --
 * droidtop's own equivalent of Daijishō's real "Add a player" form
 * (confirmed via a live screenshot of that exact screen this session: name,
 * am-start-argument text field with the same `{file.path}`/`{file.uri}`
 * placeholder convention, and a "kill package processes" toggle -- this
 * mirrors that shape directly rather than inventing a different one). Lets
 * a user wire up any emulator [KnownPlayers] doesn't already have a preset
 * for, without needing a droidtop code change.
 *
 * Plain [org.json] (already on every Android device, no new dependency)
 * rather than kotlinx-serialization -- the shape here is small and stable
 * enough that hand-rolled (de)serialization is simpler than wiring in a
 * serializer for one small prefs blob.
 */
object CustomPlayerPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_PREFIX = "droidtop_custom_players_"

    fun getForSystem(context: Context, systemId: String): List<Player.AmStart> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PREFIX + systemId, null)
            ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Player.AmStart(
                id = obj.getString("id"),
                name = obj.getString("name"),
                argumentsTemplate = obj.getString("argumentsTemplate"),
                killPackageProcesses = obj.optBoolean("killPackageProcesses", false),
                packageName = obj.getString("packageName"),
                storagePathTemplate = obj.optString("storagePathTemplate").takeIf { it.isNotEmpty() },
            )
        }
    }

    fun add(
        context: Context,
        systemId: String,
        name: String,
        argumentsTemplate: String,
        packageName: String,
        killPackageProcesses: Boolean,
        storagePathTemplate: String? = null,
    ): Player.AmStart {
        val existing = getForSystem(context, systemId)
        val newPlayer = Player.AmStart(
            id = "custom-${UUID.randomUUID()}",
            name = name,
            argumentsTemplate = argumentsTemplate,
            killPackageProcesses = killPackageProcesses,
            packageName = packageName,
            storagePathTemplate = storagePathTemplate,
        )
        save(context, systemId, existing + newPlayer)
        return newPlayer
    }

    /** Replaces the custom player with [player]'s id; a player no longer there is not added back. */
    fun update(context: Context, systemId: String, player: Player.AmStart) {
        save(context, systemId, getForSystem(context, systemId).map { if (it.id == player.id) player else it })
    }

    fun remove(context: Context, systemId: String, playerId: String) {
        save(context, systemId, getForSystem(context, systemId).filterNot { it.id == playerId })
    }

    /**
     * [players] of [systemId] as a players-database document (`{"players":[...]}`, the rows
     * [KnownPlayers] reads and droidtop-platforms' `players/` publishes), so a preset someone made
     * here can be shared as a file, loaded on another device with [importJson], or proposed for the
     * database as it is (Droidtop/tracker#248).
     */
    fun shareJson(systemId: String, players: List<Player.AmStart>): String {
        val rows = JSONArray()
        players.forEach { player ->
            rows.put(
                JSONObject().apply {
                    put("id", player.id)
                    put("systemId", systemId)
                    put("label", player.name)
                    put("pkg", player.packageName)
                    put("argumentsTemplate", player.argumentsTemplate)
                    if (player.killPackageProcesses) put("killPackageProcesses", true)
                    player.storagePathTemplate?.let { put("storagePathTemplate", it) }
                },
            )
        }
        return JSONObject().put("players", rows).toString(2)
    }

    /** What [importJson] did, in the words the settings row shows. */
    data class ImportResult(val added: Int, val refused: List<String>) {
        val message: String
            get() = buildString {
                append(if (added == 1) "Added 1 custom player" else "Added $added custom players")
                if (refused.isNotEmpty()) append(". Not added: ").append(refused.joinToString("; "))
            }
    }

    /**
     * Adds every row of a players-database document ([shareJson]'s shape, parsed by the same
     * [KnownPlayers.parse] the database itself goes through) as a custom player of the row's own
     * system, under a new id. A row [problems] finds fault with is not added and is named instead.
     * Reads and writes preferences, so not for the main thread.
     */
    fun importJson(context: Context, text: String): ImportResult {
        val rows = KnownPlayers.parse(text)
        var added = 0
        val refused = ArrayList<String>()
        rows.forEach { row ->
            val faults = problems(row.pkg, row.player.argumentsTemplate)
            if (faults.isEmpty()) {
                add(context, row.systemId, row.label, row.player.argumentsTemplate, row.pkg, row.player.killPackageProcesses, row.player.storagePathTemplate)
                added++
            } else {
                refused += "${row.label}: ${faults.first()}"
            }
        }
        return ImportResult(added, refused)
    }

    /**
     * What is wrong with a custom player's launch command before anything is started, one plain
     * sentence each; empty when nothing is. It checks only what the text itself says: that it names
     * the app it is saved for, opens it by component or package, and passes the game. Whether the
     * app really has that screen is the launch test's job ([prepareLaunch]). Pure, for tests.
     */
    fun problems(packageName: String, argumentsTemplate: String): List<String> {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return listOf("Give the package name of the app that runs the games.")
        if (argumentsTemplate.isBlank()) return listOf("Give the am start arguments that open a game in $pkg.")
        val out = ArrayList<String>()
        if (FILE_PLACEHOLDERS.none { it in argumentsTemplate }) {
            out += "The arguments never pass the game: use {file.path} or {file.uri} where the app expects the file."
        }
        // A {file.inject:...} directive reads a file beside a real game; the launch test checks those.
        if ("{file.inject:" in argumentsTemplate) return out
        val tokens = try {
            AmStartCommandToIntentConverter.tokenize(argumentsTemplate, filePath = null, fileUri = null)
        } catch (e: IllegalArgumentException) {
            return out + "The arguments cannot be read: ${e.message}."
        }
        val component = tokens.zipWithNext().firstOrNull { it.first == "-n" }?.second
        val named = component?.substringBefore('/')
            ?: tokens.zipWithNext().firstOrNull { it.first == "-p" }?.second
        when {
            named == null ->
                out += "Say which app opens the game: -n $pkg/<activity> or -p $pkg."
            named != pkg ->
                out += "The arguments open $named, but this player is saved for $pkg."
            component != null && '/' !in component ->
                out += "-n needs the package and the activity, as $pkg/.MainActivity."
        }
        return out
    }

    private val FILE_PLACEHOLDERS = listOf("{file.path}", "{file.uri}")

    private fun save(context: Context, systemId: String, players: List<Player.AmStart>) {
        val array = JSONArray()
        players.forEach { player ->
            array.put(
                JSONObject().apply {
                    put("id", player.id)
                    put("name", player.name)
                    put("argumentsTemplate", player.argumentsTemplate)
                    put("killPackageProcesses", player.killPackageProcesses)
                    put("packageName", player.packageName)
                    player.storagePathTemplate?.let { put("storagePathTemplate", it) }
                },
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + systemId, array.toString()).apply()
    }
}
