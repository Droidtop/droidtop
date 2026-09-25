package dev.droidtop.shell.gamepad.pc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.PcGameRuntimeRegistry
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.WineGameSettings
import dev.droidtop.library.WineGameSettingsPrefs
import dev.droidtop.library.lutris.ImportLine
import dev.droidtop.library.lutris.LutrisImport
import dev.droidtop.library.lutris.LutrisInstaller
import dev.droidtop.library.lutris.LutrisInstallerClient
import dev.droidtop.library.lutris.LutrisScriptRefused
import dev.droidtop.library.lutris.PrefixPlan
import dev.droidtop.library.lutris.against
import dev.droidtop.library.scraper.LutrisGameResult
import dev.droidtop.library.scraper.LutrisScraperClient
import dev.droidtop.library.scraper.ScrapeLookup
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuHint
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuSectionLabel
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Import a Lutris install script" on a Windows game's own screen --
 * docs/SPEC.md 7e3, with the threat model in
 * docs/security/2026-09-25-lutris-importer.md.
 *
 * Three levels, B going back one each: which game on lutris.net (a
 * search by this game's name), which of its installers (Wine ones
 * pickable, the others shown with their runner), and the preview. The
 * preview is the whole point: it lists what will be set for this game,
 * what will change on the prefix and whether that prefix is shared, what
 * droidtop already covers, and everything not imported with why. Nothing
 * is written until Apply or "This game only", and nothing in the script
 * is ever run.
 */
@Composable
internal fun LutrisImportScreen(
    entry: LibraryEntry,
    /** The game's name as its own screen shows it: what Lutris is searched for. */
    name: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember(entry) { mutableStateOf<ImportStage>(ImportStage.Searching) }
    var games by remember(entry) { mutableStateOf<List<LutrisGameResult>>(emptyList()) }
    var installers by remember(entry) { mutableStateOf<List<LutrisInstaller>>(emptyList()) }
    var focusIndex by remember(entry) { mutableIntStateOf(0) }

    LaunchedEffect(entry) {
        stage = withContext(Dispatchers.IO) {
            runCatching { LutrisScraperClient.search(name) }.fold(
                onSuccess = { lookup ->
                    when (lookup) {
                        is ScrapeLookup.Found -> {
                            games = lookup.value
                            ImportStage.PickGame
                        }
                        ScrapeLookup.NoMatch -> ImportStage.Message("lutris.net has no game called $name.")
                        is ScrapeLookup.Refused -> ImportStage.Message(refusedLine(lookup))
                    }
                },
                onFailure = { ImportStage.Message("lutris.net could not be reached: ${it.message ?: it}") },
            )
        }
    }

    fun openGame(game: LutrisGameResult) {
        stage = ImportStage.Loading("Reading ${game.name}'s installers from lutris.net…")
        focusIndex = 0
        scope.launch {
            stage = withContext(Dispatchers.IO) {
                runCatching { LutrisInstallerClient.forGame(game.slug) }.fold(
                    onSuccess = { lookup ->
                        when (lookup) {
                            is ScrapeLookup.Found -> {
                                installers = lookup.value
                                ImportStage.PickInstaller(game)
                            }
                            ScrapeLookup.NoMatch -> ImportStage.Message("lutris.net has no installers for ${game.name}.")
                            is ScrapeLookup.Refused -> ImportStage.Message(refusedLine(lookup))
                        }
                    },
                    onFailure = {
                        ImportStage.Message(
                            if (it is LutrisScriptRefused) it.message.orEmpty() else "lutris.net could not be reached: ${it.message ?: it}",
                        )
                    },
                )
            }
        }
    }

    fun openInstaller(game: LutrisGameResult, installer: LutrisInstaller) {
        if (!installer.importable) return
        stage = ImportStage.Loading("Reading the ${installer.version} script…")
        focusIndex = 0
        scope.launch {
            stage = withContext(Dispatchers.IO) { preview(context, entry, game, installer) }
        }
    }

    fun apply(preview: ImportStage.Preview, includePrefix: Boolean) {
        stage = ImportStage.Loading("Saving…")
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                val said = mutableListOf<String>()
                preview.game?.let {
                    WineGameSettingsPrefs.set(context, entry.id, it)
                    said += "This game now runs ${it.executable}."
                }
                val changes = preview.prefix?.changes
                if (includePrefix && changes != null && !changes.isEmpty) {
                    val result = PcGameRuntimeRegistry.runtime?.applyPrefixChanges(entry.id, changes)
                    said += when {
                        result == null -> "The prefix was not changed: Windows games are not available in this build."
                        result.succeeded -> "${result.detail}."
                        else -> result.detail
                    }
                }
                said.joinToString(" ").ifEmpty { "Nothing was changed." }
            }
            onDone(message)
        }
    }

    // The rows A can act on at this level; everything else is text.
    val current = stage
    val rows: List<ImportRow> = when (current) {
        ImportStage.PickGame -> games.map { game ->
            ImportRow(game.name, game.year?.toString(), enabled = true) { openGame(game) }
        }
        is ImportStage.PickInstaller -> installers.map { installer ->
            ImportRow(
                installer.version.ifBlank { installer.slug },
                if (installer.importable) "Wine script" else "For Lutris's ${installer.runner} runner; droidtop imports Wine scripts only",
                enabled = installer.importable,
            ) { openInstaller(current.game, installer) }
        }
        is ImportStage.Preview -> listOfNotNull(
            ImportRow("Apply", current.applyLine(), enabled = current.hasAnything) { apply(current, includePrefix = true) },
            if (current.game != null && current.prefix?.changes?.isEmpty == false) {
                ImportRow("This game only", "Sets this game's program and leaves the prefix alone", enabled = true) {
                    apply(current, includePrefix = false)
                }
            } else {
                null
            },
            ImportRow("Cancel", "Changes nothing", enabled = true) { onDismiss() },
        )
        else -> emptyList()
    }

    fun back() {
        focusIndex = 0
        when (current) {
            is ImportStage.Preview -> stage = ImportStage.PickInstaller(current.lutrisGame)
            is ImportStage.PickInstaller -> stage = ImportStage.PickGame
            else -> onDismiss()
        }
    }

    BackHandler { back() }
    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(680.dp)),
            focusLabel = "Import a Lutris install script",
            onKey = { event ->
                if (event.type != KeyEventType.KeyUp) {
                    false
                } else {
                    when (GamepadKeyMap.actionFor(event.key)) {
                        GamepadAction.UP -> {
                            if (rows.isNotEmpty()) {
                                focusIndex = (focusIndex - 1 + rows.size) % rows.size
                                EsDeNavigationSounds.play("scroll")
                            }
                            true
                        }
                        GamepadAction.DOWN -> {
                            if (rows.isNotEmpty()) {
                                focusIndex = (focusIndex + 1) % rows.size
                                EsDeNavigationSounds.play("scroll")
                            }
                            true
                        }
                        GamepadAction.A -> {
                            rows.getOrNull(focusIndex)?.takeIf { it.enabled }?.onSelect?.invoke()
                            true
                        }
                        GamepadAction.B, GamepadAction.BACK -> {
                            back()
                            true
                        }
                        else -> false
                    }
                }
            },
        ) {
            Text(
                current.title(name),
                style = MaterialTheme.typography.titleMedium,
                color = MenuTokens.OnSurface,
                fontWeight = FontWeight.SemiBold,
            )
            when (current) {
                ImportStage.Searching -> MenuHint("Searching lutris.net for $name…")
                is ImportStage.Loading -> MenuHint(current.line)
                is ImportStage.Message -> MenuHint(current.line)
                else -> Unit
            }
            rows.forEachIndexed { index, row ->
                MenuRow(
                    title = row.title,
                    subtitle = row.subtitle,
                    selected = index == focusIndex,
                    placeholder = !row.enabled,
                    onClick = if (row.enabled) {
                        {
                            focusIndex = index
                            row.onSelect()
                        }
                    } else {
                        null
                    },
                )
            }
            if (current is ImportStage.Preview) PreviewBody(current)
            MenuHint(
                when (current) {
                    is ImportStage.Preview -> "Nothing in the script is run. Up/Down moves, A picks, B goes back"
                    ImportStage.PickGame, is ImportStage.PickInstaller -> "Up/Down moves, A picks, B goes back"
                    else -> "B goes back"
                },
            )
        }
    }
}

@Composable
private fun PreviewBody(preview: ImportStage.Preview) {
    MenuSectionLabel("For this game")
    val game = preview.game
    if (game == null) {
        MenuHint("Nothing: droidtop keeps choosing this game's program itself.")
    } else {
        Lines(
            listOfNotNull(
                ImportLine("Program", game.executable.orEmpty()),
                game.arguments.takeIf { it.isNotEmpty() }?.let { ImportLine("Arguments", it.joinToString(" ")) },
                game.workingDir?.let { ImportLine("Starts in", it) },
            ),
        )
    }
    val prefix = preview.prefix
    if (prefix != null && (prefix.lines.isNotEmpty() || prefix.alreadySet.isNotEmpty())) {
        MenuSectionLabel("On the prefix ${preview.prefixName}")
        if (preview.prefixShared) {
            MenuHint("Every Windows game without a prefix of its own runs in this one, so these change them too.")
        }
        if (prefix.lines.isEmpty()) MenuHint("Nothing to change.") else Lines(prefix.lines)
    }
    val covered = preview.covered + prefix?.alreadySet.orEmpty()
    if (covered.isNotEmpty()) {
        MenuSectionLabel("Already covered")
        Lines(covered)
    }
    if (preview.notImported.isNotEmpty()) {
        MenuSectionLabel("Not imported, needs manual setup")
        Lines(preview.notImported)
    }
}

@Composable
private fun Lines(lines: List<ImportLine>) {
    lines.forEach { line ->
        Text(line.what, color = MenuTokens.OnSurface, style = MaterialTheme.typography.bodyMedium)
        if (line.detail.isNotBlank()) {
            Text(line.detail, color = MenuTokens.Value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A row A can act on; a disabled one is drawn and says why. */
private data class ImportRow(val title: String, val subtitle: String?, val enabled: Boolean, val onSelect: () -> Unit)

private sealed interface ImportStage {
    data object Searching : ImportStage
    data object PickGame : ImportStage
    data class PickInstaller(val game: LutrisGameResult) : ImportStage
    data class Loading(val line: String) : ImportStage
    data class Message(val line: String) : ImportStage

    /** Everything the preview shows, worked out off the main thread. */
    data class Preview(
        val lutrisGame: LutrisGameResult,
        val installerLabel: String,
        val game: WineGameSettings?,
        val prefix: PrefixPlan?,
        val prefixName: String,
        val prefixShared: Boolean,
        val covered: List<ImportLine>,
        val notImported: List<ImportLine>,
    ) : ImportStage {
        val hasAnything: Boolean get() = game != null || prefix?.changes?.isEmpty == false

        fun applyLine(): String = when {
            !hasAnything -> "Nothing in this script can be imported"
            game != null && prefix?.changes?.isEmpty == false -> "Sets this game's program and the prefix changes below"
            game != null -> "Sets this game's program"
            else -> "Makes the prefix changes below"
        }
    }

    fun title(name: String): String = when (this) {
        is PickInstaller -> "Which ${game.name} installer?"
        is Preview -> "Import $installerLabel"
        else -> "Import a Lutris script for $name"
    }
}

/** Disk and provider work: the IO dispatcher only. */
private fun preview(
    context: android.content.Context,
    entry: LibraryEntry,
    game: LutrisGameResult,
    installer: LutrisInstaller,
): ImportStage {
    val plan = try {
        LutrisImport.translate(installer.script)
    } catch (refused: LutrisScriptRefused) {
        return ImportStage.Message("This script was not read: ${refused.message}.")
    }
    val notImported = plan.notImported.toMutableList()
    val folder = PcRunnerOptions.gameFolderFor(entry)
    val inFolder = folder?.let { LutrisImport.resolveInFolder(plan, it) }
    val exePath = plan.exePath
    if (folder == null && exePath != null) {
        notImported += ImportLine("Executable ${exePath.last()}", "This game has no folder on this device to find it in")
    }
    inFolder?.let { notImported += it.notImported }
    val label = listOf(installer.name, installer.version).filter { it.isNotBlank() }.joinToString(" - ")
    val gameSettings = inFolder?.let { found ->
        found.executable?.let { exe ->
            WineGameSettings(
                executable = exe,
                arguments = plan.args.orEmpty(),
                workingDir = found.workingDir?.takeIf { it.isNotEmpty() },
                source = "Lutris: $label",
            )
        }
    }
    val args = plan.args
    if (gameSettings == null && args != null) {
        notImported += ImportLine("Arguments ${args.joinToString(" ")}", "They belong to the program above, which was not imported")
    }
    val runtime = PcGameRuntimeRegistry.runtime
    val state = runtime?.prefixState(entry.id)
    if (state == null && !plan.prefix.isEmpty) {
        notImported += ImportLine(
            "Prefix settings",
            "There is no Windows prefix yet: set up Windows games, then import again",
        )
    }
    return ImportStage.Preview(
        lutrisGame = game,
        installerLabel = label,
        game = gameSettings,
        prefix = state?.let { plan.prefix.against(it) },
        prefixName = state?.name.orEmpty(),
        prefixShared = state?.shared == true,
        covered = plan.covered,
        notImported = notImported,
    )
}

private fun refusedLine(refused: ScrapeLookup.Refused): String =
    "lutris.net refused the request (HTTP ${refused.httpStatus})" + (refused.reason?.let { ": $it" } ?: ".")
