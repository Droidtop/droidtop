package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.TextInputItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One Windows game's own properties as a settings screen (docs/SPEC.md 7i,
 * "Game properties", Droidtop/tracker#228): the launch options the person
 * types after the program, the environment variables they set for it, the
 * game's language and its pad layout, and the rows that say exactly what
 * will run. The page every guide ends with
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
    private const val DEVICE_LANGUAGE = "device"
    private const val GAMEPAD = "gamepad"

    /** What the last environment edit refused, by game, until the next edit: a text field cannot answer itself. */
    private val refusals = ConcurrentHashMap<String, String>()

    fun screen(entryId: String, title: String, gameRoot: String): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Game properties",
        subtitle = title,
        groups = { context -> withContext(Dispatchers.IO) { groups(context, entryId, title, File(gameRoot)) } },
        indexGroups = { _ -> emptyList() },
    )

    private suspend fun groups(context: Context, entryId: String, title: String, gameRoot: File): List<CatalogGroup> {
        val settings = WineGameSettingsPrefs.get(context, entryId)
        val options = settings?.launchOptions.orEmpty()
        val environment = settings?.environment.orEmpty()
        val controls = GamePadLayoutPrefs.layout(context, entryId)
        val edit = buildList<CatalogItem> {
            add(
                ChoiceItem(
                    id = "properties_language",
                    title = "Language",
                    subtitle = "The language the game and Steamworks are told; a download's own is set at install",
                    options = listOf(ChoiceOption(DEVICE_LANGUAGE, "Device language")) +
                        GameLanguage.entries.map { ChoiceOption(it.steam, it.label) },
                    current = GameLanguage.bySteam(settings?.language)?.steam ?: DEVICE_LANGUAGE,
                    onSelect = { ctx, value ->
                        val language = GameLanguage.bySteam(value)?.steam
                        CoroutineScope(Dispatchers.IO).launch { WineGameSettingsPrefs.edit(ctx, entryId) { it.copy(language = language) } }
                    },
                ),
            )
            add(
                ChoiceItem(
                    id = "properties_controls",
                    title = "Controls",
                    subtitle = "How the pad plays this game; the running game's L2 sheet changes it too",
                    options = listOf(ChoiceOption(GAMEPAD, GamePadLayouts.GAMEPAD_LABEL)) +
                        GamePadLayouts.entries.map { ChoiceOption(it.id, it.label) },
                    current = controls?.id ?: GAMEPAD,
                    onSelect = { ctx, value ->
                        CoroutineScope(Dispatchers.IO).launch { GamePadLayoutPrefs.setLayout(ctx, entryId, GamePadLayouts.byId(value)) }
                    },
                ),
            )
            add(
                TextInputItem(
                    id = "properties_launch_options",
                    title = "Launch options",
                    subtitle = "Added after the program's own, one per word; quote words with spaces. Never run by a shell",
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
        val drivers = listOf(
            dev.droidtop.library.settings.NestedScreenItem(
                id = "properties_drivers",
                title = "Drivers",
                subtitle = "Kernel drivers this game installs (anti-cheat, copy protection), and which are approved",
                inline = GameDrivers.screen(entryId, title),
            ),
        )
        val launch = WindowsLaunchResolver.forEntry(context, entryId, gameRoot)
        val show = if (launch == null) {
            listOf(ActionItem("properties_command_none", "Program", "droidtop can't tell which program is the game here; choose one under Program"))
        } else {
            listOf(
                ActionItem("properties_command_program", "Program", launch.executable.path.removePrefix(gameRoot.path).trimStart('/').ifEmpty { launch.executable.name }),
                ActionItem(
                    "properties_command_args",
                    "Arguments",
                    launch.arguments.joinToString(" ") { if (it.any(Char::isWhitespace) || it.isEmpty()) "\"$it\"" else it }.ifEmpty { "None" },
                ),
                ActionItem("properties_command_dir", "Starts in", launch.workingDir.path),
                ActionItem(
                    "properties_command_env",
                    "Environment",
                    GameLaunchOptions.launchEnvironment(settings).ifEmpty { "None of its own; the prefix's variables apply" },
                ),
            )
        }
        return listOf(
            CatalogGroup("properties_edit", null, edit),
            CatalogGroup("properties_drivers", null, drivers),
            CatalogGroup("properties_command", "What will run", show),
        )
    }
}
