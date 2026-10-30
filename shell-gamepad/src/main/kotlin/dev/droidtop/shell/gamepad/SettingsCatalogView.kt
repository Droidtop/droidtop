package dev.droidtop.shell.gamepad

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.theme.ThemeBrowserScreen

/**
 * Gaming's Settings section: the Gaming settings catalog rendered by
 * [CatalogNavigator], with the shell's renderer-native fulfillments
 * (Browse themes opens
 * [ThemeBrowserScreen] inline, also reachable by deep link via
 * [browseThemesToken]).
 */
@Composable
internal fun SettingsCatalogView(
    onBack: () -> Unit,
    browseThemesToken: Int = 0,
    placeScreenIds: Set<String> = emptySet(),
    onHelpRowClaim: (HelpRowClaim) -> Unit = {},
) {
    var browseThemes by remember { mutableStateOf(false) }
    // Browse themes draws its own hint row (its A is "Download or update"),
    // so while it is up the shell's row stands down: both were drawn,
    // stacked (rig, dq-shell2-02). One row per screen (SPEC 7j).
    LaunchedEffect(browseThemes) {
        onHelpRowClaim(if (browseThemes) HelpRowClaim.SCREEN else HelpRowClaim.NONE)
    }

    LaunchedEffect(browseThemesToken) {
        if (browseThemesToken > 0) browseThemes = true
    }

    if (browseThemes) {
        ThemeBrowserScreen(onDismiss = { browseThemes = false })
        return
    }

    val root = remember(placeScreenIds) { gamingSettingsRoot(placeScreenIds) }
    CatalogNavigator(
        root = root,
        onExit = onBack,
        nativeActions = mapOf(
            GamingSettingsCatalog.ID_BROWSE_THEMES to { browseThemes = true },
        ),
        showSearch = true,
        placeScreenIds = placeScreenIds,
    )
}

/**
 * The Gaming shell's Settings, as one screen: what the Settings page opens and what the one search indexes, so a
 * setting the search finds is a setting the page shows. [placeScreenIds] are the places the left menu already has,
 * which Settings does not repeat.
 */
internal fun gamingSettingsRoot(placeScreenIds: Set<String> = emptySet()): CatalogScreen = CatalogScreen(
    id = "gaming_settings",
    title = "Settings",
    categoryOrder = GamingSettingsCatalog.CATEGORY_ORDER,
    // Merged first, then filtered: the places Global settings links to (Updates) were merged in after the filter ran,
    // so Settings kept a second Updates (Droidtop/tracker#610).
    groups = { ctx ->
        withoutPlaceLinks(mergeShortScreens(GamingSettingsCatalog.settingsGroups(ctx)) { it.groups(ctx) }, placeScreenIds)
    },
)

/**
 * A place from the left menu (Stores, Downloads and jobs, Updates,
 * Plugins; docs/SPEC.md 7j "Places"): one registered settings screen
 * drawn in place of the section's content, by the same navigator Settings
 * uses, so it has the same B, Info sheet and touch behaviour. The screens
 * live in :app and :library-core and are found by registry id, because
 * this module cannot depend on them.
 */
@Composable
internal fun PlaceCatalogView(
    screenId: String,
    onBack: () -> Unit,
    nativeActions: Map<String, () -> Unit> = emptyMap(),
) {
    val screen = remember(screenId) { SettingsScreenRegistry.get(screenId) }
    if (screen == null) {
        BackHandler(onBack = onBack)
        Text(stringResource(R.string.page_not_in_build), color = MenuTokens.OnSurfaceMuted)
        return
    }
    CatalogNavigator(root = screen, onExit = onBack, nativeActions = nativeActions)
}
