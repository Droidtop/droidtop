package dev.droidtop.shell.gamepad

import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.words
import dev.droidtop.runtime.R

/**
 * droidtop's Settings, one tree for every mode (docs/SPEC.md "One settings tree", mytop-project/tracker#964 step 10): the
 * Gaming shell's Settings page, Mobile's Settings and Desktop's Start > Settings all open this screen, so a setting is in
 * the same place whichever mode a person is in, and the one search indexes what the page shows. [placeScreenIds] are the
 * places a shell's own menu already has (the Gaming left menu), which Settings then does not repeat; registered as
 * [SCREEN_ID] with none.
 */
object SettingsRoot {
    const val SCREEN_ID = "settings"

    fun screen(placeScreenIds: Set<String> = emptySet()): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = words(R.string.gaming_settings),
        categoryOrder = GamingSettingsCatalog.CATEGORY_ORDER,
        // Merged first, then filtered: the places Global settings links to (Updates) were merged in after the filter ran,
        // so Settings kept a second Updates (mytop-project/tracker#610).
        groups = { ctx ->
            withoutPlaceLinks(mergeShortScreens(GamingSettingsCatalog.settingsGroups(ctx)) { it.groups(ctx) }, placeScreenIds)
        },
    )
}
