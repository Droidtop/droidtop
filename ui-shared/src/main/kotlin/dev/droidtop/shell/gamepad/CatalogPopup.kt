package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.shell.gamepad.input.DeclareLayerHints
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HintBinding

/** The hint bar of a catalog screen opened over another (CatalogPopup, and the shell's CatalogSheet). */
val CATALOG_SHEET_HINTS = listOf(HintBinding(GamepadAction.A, "Select"), HintBinding(GamepadAction.B, "Back"))

/**
 * A catalog screen as a popup over the screen it was opened from (owner, 2026-11-15, Droidtop/tracker#939: "Facets
 * shouldn't be entire pages, generally. They should be popups or overlays. A facet shouldn't take over, it should give
 * information to the main UI"). A panel of the shell's panel width, no taller than the content area, drawn on the
 * overlay surface over a dimmed copy of what is under it, which stays on screen. B and the screen's own exit close it,
 * and the screen under it shows what it changed. For a facet's small screen (search options, a source's choices, a
 * plugin's page call, a settings row marked [dev.droidtop.library.settings.NestedScreenItem.popup]); the shell's
 * CatalogSheet stays for a page that is a place of its own.
 */
@Composable
fun CatalogPopup(root: CatalogScreen, onExit: () -> Unit) {
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
                .padding(top = window.frameBarHeight, bottom = window.frameBarHeight),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(window.panelWidth(560.dp))
                    .heightIn(max = (window.heightDp * POPUP_MAX_HEIGHT).dp)
                    .clip(MenuTokens.OverlayShape)
                    .background(MenuTokens.OverlaySurface),
            ) {
                CatalogNavigator(root = root, onExit = onExit)
            }
        }
    }
}

/** The share of the window a popup may take, so the screen under it stays in sight. */
private const val POPUP_MAX_HEIGHT = 0.7f
