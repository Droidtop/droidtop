package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.TextInputItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One Windows game's own properties as a settings screen (docs/SPEC.md 7i,
 * "Game properties", Droidtop/tracker#228): the launch options the person
 * types after the program, the environment variables they set for it, and
 * the rows that say exactly what will run. The page every guide ends with
 * ("add this flag", "set DXVK_HUD"), reachable from the game's menu and
 * written through the one place the launch reads, [WineGameSettings].
 *
 * Nothing here runs a script: pre-launch and post-exit commands are not
 * offered (docs/SPEC.md 7e3, threat model: droidtop runs no command from
 * text a person pastes), and the options are handed to the program as
 * separate arguments, never through a shell.
 */
object GameProperties {
    const val SCREEN_ID = "windows_game_properties"

    /** What the last environment edit refused, by game, until the next edit: a text field cannot answer itself. */
    private val refusals = ConcurrentHashMap<String, String>()

    fun screen(entryId: String, title: String, gameRoot: String): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Game properties",
        subtitle = title,
        groups = { context -> withContext(Dispatchers.IO) { groups(context, entryId, File(gameRoot)) } },
        indexGroups = { _ -> emptyList() },
    )

    private suspend fun groups(context: Context, entryId: String, gameRoot: File): List<CatalogGroup> {
        val settings = WineGameSettingsPrefs.get(context, entryId)
        val options = settings?.launchOptions.orEmpty()
        val environment = settings?.environment.orEmpty()
        val edit = buildList<CatalogItem> {
            add(
                TextInputItem(
                    id = "properties_launch_options",
                    title = "Launch options",
                    subtitle = "Added after the program's own arguments, one argument per word; use quotes around a word with spaces. " +
                        "Handed to the program as it is, never through a shell",
                    value = options,
                    onChange = { ctx, text ->
                        withContext(Dispatchers.IO) {
                            WineGameSettingsPrefs.edit(ctx, entryId) {
                                it.copy(launchOptions = text.trim().take(GameLaunchOptions.MAX_OPTIONS_LENGTH).ifEmpty { null })
                            }
                        }
                    },
                ),
            )
            add(
                TextInputItem(
                    id = "properties_environment",
                    title = "Environment variables",
                    subtitle = refusals[entryId]?.let { "Not kept: $it" }
                        ?: "NAME=value, separated by spaces. Tuning variables only: DXVK_, VKD3D_, MESA_, __GL_ and a few more. " +
                            "They apply to this game's launch and never change the prefix",
                    value = GameLaunchOptions.formatEnvironment(environment),
                    onChange = { ctx, text ->
                        val parsed = GameLaunchOptions.parseEnvironment(text)
                        if (parsed.refused.isEmpty()) refusals.remove(entryId) else refusals[entryId] = parsed.refused.joinToString("; ")
                        withContext(Dispatchers.IO) {
                            WineGameSettingsPrefs.edit(ctx, entryId) { it.copy(environment = parsed.variables) }
                        }
                    },
                ),
            )
            if (options.isNotBlank() || environment.isNotEmpty()) {
                add(
                    AsyncActionItem(
                        id = "properties_reset",
                        title = "Reset",
                        subtitle = "Clears the launch options and the variables; the program choice stays",
                        confirmTitle = "Clear this game's launch options and variables?",
                        run = { ctx, _ ->
                            withContext(Dispatchers.IO) {
                                WineGameSettingsPrefs.edit(ctx, entryId) { it.copy(launchOptions = null, environment = emptyMap()) }
                            }
                            refusals.remove(entryId)
                            "Cleared"
                        },
                    ),
                )
            }
        }
        val launch = WindowsLaunchResolver.forEntry(context, entryId, gameRoot)
        val show = if (launch == null) {
            listOf(ActionItem("properties_command_none", "Program", "droidtop can't tell which program is the game here; choose one under Program", run = {}))
        } else {
            listOf(
                ActionItem("properties_command_program", "Program", launch.executable.path.removePrefix(gameRoot.path).trimStart('/').ifEmpty { launch.executable.name }, run = {}),
                ActionItem(
                    "properties_command_args",
                    "Arguments",
                    launch.arguments.joinToString(" ") { if (it.any(Char::isWhitespace) || it.isEmpty()) "\"$it\"" else it }.ifEmpty { "None" },
                    run = {},
                ),
                ActionItem("properties_command_dir", "Starts in", launch.workingDir.path, run = {}),
                ActionItem(
                    "properties_command_env",
                    "Environment",
                    GameLaunchOptions.launchEnvironment(environment).ifEmpty { "None of its own; the prefix's variables apply" },
                    run = {},
                ),
            )
        }
        return listOf(
            CatalogGroup("properties_edit", null, edit),
            CatalogGroup("properties_command", "What will run", show),
        )
    }
}
