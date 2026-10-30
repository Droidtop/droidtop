package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.library.integrations.GetGamesEntry
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.input.DeclareLayerHints
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding

private val CATALOG_SHEET_HINTS = listOf(HintBinding(GamepadAction.A, "Select"), HintBinding(GamepadAction.B, "Back"))

/**
 * A catalog screen in a sheet kept inside Gaming's content area, clear of the persistent top and
 * bottom bars (Droidtop/tracker#218). B and the screen's own exit close it. It is a layer of the
 * hint bar ([DeclareLayerHints]) and paints the ground under itself, so the screen it opened over
 * neither shows through nor keeps its hints.
 */
@Composable
internal fun CatalogSheet(root: CatalogScreen, onExit: () -> Unit) {
    val window = LocalShellWindow.current
    ShellDialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GatePadInThisDialog()
        DeclareLayerHints(CATALOG_SHEET_HINTS)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = window.frameBarHeight, bottom = window.frameBarHeight)
                .background(MenuTokens.Ground),
        ) {
            CatalogNavigator(root = root, onExit = onExit)
        }
    }
}

/**
 * The "Get games" entry as a chip, for the places that are rows of chips (an empty state, a game's page, the Apps
 * section): it owns whether its search is open, so a caller is one line. [systemId] scopes it to a console system;
 * [pc] searches for PC games.
 */
@Composable
internal fun GetGamesChip(
    modifier: Modifier = Modifier,
    systemId: String? = null,
    pc: Boolean = false,
    primary: Boolean = false,
    onChanged: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    ShellChip(GetGamesEntry.LABEL, modifier = modifier, primary = primary, onClick = { open = true })
    if (open) {
        GetGamesSearch(systemId, pc = pc) {
            open = false
            onChanged()
        }
    }
}

/**
 * What every "Get games" entry opens (docs/SPEC.md 13, "There is no Get games sheet"; owner, 2026-10-16,
 * Droidtop/tracker#218): the one search, empty and ready for typing, where the download sources answer. A console
 * [systemId] searches that system's sources and downloads to its folder; [pc] asks the sources for PC games. It has
 * no device rows of its own: the place it was opened from already lists what is here.
 */
@Composable
fun GetGamesSearch(
    systemId: String? = null,
    systemFolder: java.io.File? = null,
    pc: Boolean = false,
    // What the search starts with (a title picked on Get new games), and whether the person chose to type.
    text: String = "",
    startTyping: Boolean = false,
    onDismiss: () -> Unit,
) {
    dev.droidtop.shell.gamepad.query.LibrarySearchDialog(
        query = dev.droidtop.shell.gamepad.query.LibraryQuery(text = text),
        startTyping = startTyping,
        matchCount = 0,
        totalCount = 0,
        onTextChange = {},
        onDismiss = onDismiss,
        systemId = GetGamesEntry.searchSystem(systemId),
        systemFolder = systemFolder,
        local = { emptyList() },
        sourcePlatform = if (pc) dev.droidtop.library.integrations.SourceDestinations.PC_PLATFORM else null,
        getGames = true,
    )
}

/**
 * "Get apps" as a chip: the app catalogs screen (F-Droid, GitHub, whatever the plugins add) that also
 * lives under Updates, one registered screen with two doors (Droidtop/tracker#501).
 */
@Composable
internal fun GetAppsChip(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ShellChip("Get apps", modifier = modifier, onClick = { open = true })
    if (open) {
        val screen = remember { SettingsScreenRegistry.get(dev.droidtop.library.integrations.AppCatalogScreen.ID) }
        if (screen == null) {
            androidx.compose.runtime.LaunchedEffect(Unit) { open = false }
        } else {
            CatalogSheet(screen) { open = false }
        }
    }
}
