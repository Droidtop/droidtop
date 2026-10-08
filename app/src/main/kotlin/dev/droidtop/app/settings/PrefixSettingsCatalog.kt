package dev.droidtop.app.settings

import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.runtime.windows.PrefixSetting
import dev.droidtop.runtime.windows.PrefixSettings

/**
 * All of a Wine prefix's settings as droidtop's own two-pane settings rows
 * (docs/SPEC.md 7c): display, audio, controller, CPU, Wine, Windows
 * components, environment and drives. Reached from the "All prefix settings"
 * row of Wine and graphics ([WineOptionsCatalog]). [PrefixSettings] decides
 * where each value is kept.
 */
object PrefixSettingsCatalog {

    /** The screen for [entryId]'s prefix, or the shared one when it is null. */
    fun screen(entryId: String?, title: String?): CatalogScreen = CatalogScreen(
        id = "wine_prefix_settings",
        title = "All prefix settings",
        subtitle = title,
        groups = { context ->
            val state = PrefixSettings.state(context, entryId)
            if (state == null) {
                listOf(
                    CatalogGroup(
                        id = "wine_prefix_none",
                        title = null,
                        items = listOf(ActionItem(id = "wine_prefix_not_set_up", title = "No Windows environment yet", value = "Set up Windows games first", run = {})),
                    ),
                )
            } else {
                state.sections.map { section ->
                    CatalogGroup(
                        id = "wine_prefix_" + section.id,
                        title = section.title,
                        items = section.rows.map { item(it, entryId) },
                    )
                }
            }
        },
        // Per-prefix rows belong to the screen, not to settings search.
        indexGroups = { _ -> emptyList() },
    )

    private fun item(row: PrefixSetting, entryId: String?): CatalogItem = when (row) {
        is PrefixSetting.Choice -> ChoiceItem(
            id = row.id,
            title = row.title,
            options = row.choices.map { ChoiceOption(it.value, it.label) },
            current = row.current,
            onSelect = { ctx, value -> PrefixSettings.set(ctx, entryId, row.id, value) },
        )
        is PrefixSetting.Toggle -> ToggleItem(
            id = row.id,
            title = row.title,
            current = row.current,
            onToggle = { ctx, on -> PrefixSettings.set(ctx, entryId, row.id, on.toString()).join() },
        )
        is PrefixSetting.Text -> TextInputItem(
            id = row.id,
            title = row.title,
            value = row.current,
            onChange = { ctx, text -> PrefixSettings.set(ctx, entryId, row.id, text).join() },
        )
        is PrefixSetting.Slider -> SliderItem(
            id = row.id,
            title = row.title,
            min = row.min,
            max = row.max,
            current = row.current,
            onChange = { ctx, value -> PrefixSettings.set(ctx, entryId, row.id, value.toString()) },
        )
        is PrefixSetting.Info -> ActionItem(id = row.id, title = row.title, value = row.value, run = {})
    }
}
