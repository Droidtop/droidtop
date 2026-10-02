package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.library.integrations.AcquireContentSources
import dev.droidtop.library.integrations.GetGamesContext
import dev.droidtop.library.integrations.GetGamesEntry
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.SettingsScreenRegistry

/**
 * The ONE "Get games" action (docs/SPEC.md 12a "Get games everywhere"). A menu, a page, an empty
 * state or the Quick Menu shows a row labelled [GetGamesEntry.LABEL] and, when it is pressed,
 * either sets `acquireScreen = getGamesScreen(...)` to host it in its own sheet or composes
 * [GetGamesSheet]. The screen asks which system when [context] has none, opens that system's
 * source list when it has one, and says so and leads to Plugins when no source is installed.
 * A new surface (the left menu, #258) adds a row that calls this; it never builds its own.
 */
internal fun getGamesScreen(context: GetGamesContext, systemId: String? = null): CatalogScreen? =
    SettingsScreenRegistry.get(AcquireContentSources.GET_GAMES_SCREEN_ID, GetGamesEntry.systemFor(context, systemId))

/**
 * A catalog screen in a sheet kept inside Gaming's content area, clear of the persistent top and
 * bottom bars (Droidtop/tracker#218). B and the screen's own exit close it.
 */
@Composable
internal fun CatalogSheet(root: CatalogScreen, onExit: () -> Unit) {
    val window = LocalShellWindow.current
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = window.frameBarHeight, bottom = window.frameBarHeight),
        ) {
            CatalogNavigator(root = root, onExit = onExit)
        }
    }
}

/**
 * The "Get games" entry as a chip, for the places that are rows of chips (an empty state, a game's
 * page, the Apps section): it owns whether its sheet is open, so a caller is one line.
 */
@Composable
internal fun GetGamesChip(
    context: GetGamesContext,
    modifier: Modifier = Modifier,
    systemId: String? = null,
    primary: Boolean = false,
    onChanged: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    ShellChip(GetGamesEntry.LABEL, modifier = modifier, primary = primary, onClick = { open = true })
    if (open) GetGamesSheet(context, systemId, onDismiss = { open = false }, onChanged = onChanged)
}

/**
 * The "Get games" screen for [context] in a [CatalogSheet]. [onChanged] runs when it closes, so a
 * list that a download may have just filled can rescan.
 */
@Composable
internal fun GetGamesSheet(
    context: GetGamesContext,
    systemId: String? = null,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {},
) {
    val screen = androidx.compose.runtime.remember(context, systemId) { getGamesScreen(context, systemId) }
    if (screen == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }
        return
    }
    CatalogSheet(screen) {
        onDismiss()
        onChanged()
    }
}
