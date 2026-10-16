package dev.droidtop.shell.gamepad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.integrations.PluginContextActions
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.pluginhost.PluginModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plugin actions (docs/plugin-api.md 3 C4, Droidtop/tracker#67) in Gaming's menus on one game or app: the PC game
 * menu (Y) and an app's Select menu. The same page Standard's and Desktop's menus open
 * ([PluginContextActions.screen]), shown in the menu's own sheet; a row says "Plugin actions" only when a plugin's
 * static filter matches, read off the main thread from manifests alone.
 */
@Composable
internal fun rememberHasPluginActions(entry: LibraryEntry): Boolean {
    val context = LocalContext.current
    return produceState(false, entry.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                PluginContextActions.actionsFor(context, PluginContextActions.targetOf(entry), PluginModes.GAMING).isNotEmpty()
            }.getOrDefault(false)
        }
    }.value
}

/** The actions page for [entry] at [place]; a plugin's reply view replaces it through [onScreen]. */
internal fun pluginActionsScreen(entry: LibraryEntry, place: String, onScreen: (CatalogScreen) -> Unit): CatalogScreen =
    PluginContextActions.screen(PluginContextActions.targetOf(entry), PluginModes.GAMING, place, onScreen)

internal const val PLUGIN_ACTIONS_LABEL = "Plugin actions"
