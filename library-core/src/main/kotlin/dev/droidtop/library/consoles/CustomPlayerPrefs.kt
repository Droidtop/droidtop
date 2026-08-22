package dev.droidtop.library.consoles

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    private const val PREFS_NAME = "com.android.launcher3.prefs"
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
            )
        }
    }

    fun add(context: Context, systemId: String, name: String, argumentsTemplate: String, packageName: String, killPackageProcesses: Boolean) {
        val existing = getForSystem(context, systemId)
        val newPlayer = Player.AmStart(
            id = "custom-${UUID.randomUUID()}",
            name = name,
            argumentsTemplate = argumentsTemplate,
            killPackageProcesses = killPackageProcesses,
            packageName = packageName,
        )
        save(context, systemId, existing + newPlayer)
    }

    fun remove(context: Context, systemId: String, playerId: String) {
        save(context, systemId, getForSystem(context, systemId).filterNot { it.id == playerId })
    }

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
                },
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + systemId, array.toString()).apply()
    }
}
