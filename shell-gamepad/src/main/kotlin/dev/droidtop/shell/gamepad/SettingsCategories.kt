package dev.droidtop.shell.gamepad

import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem

/*
 * The settings layout's pure rules (docs/SPEC.md "Settings layout"): which
 * entries the category column holds, and which short screens are drawn as
 * sections of the pane that links them. `CatalogNavigator` draws them; these
 * decide, so they can be tested without a screen.
 */

/**
 * One entry of the settings category column. A plain category shows the rows
 * of its groups ([groupIds]: one group, or every group that names the
 * category); a link category shows the screen [link] opens,
 * directly in the pane, one level less than opening it from a row.
 * [sectionAbove] is the column label drawn above the entry (the title of the
 * group its links came from, on the first of them only).
 */
internal data class SettingsCategory(
    val key: String,
    val label: String,
    val icon: CatalogIcon?,
    val sectionAbove: String?,
    val groupIds: List<String>,
    val link: NestedScreenItem?,
)

/**
 * Fewer entries than this and a catalog has no category column: it is one
 * list. Two would split a short page (Plugins: its list and "Add") in half.
 */
internal const val MIN_SETTINGS_CATEGORIES = 3

/**
 * The category column for a screen's [groups]. A group made only of links to
 * other screens (Library: Scraper, Console systems, Emulators, ...) is a hub:
 * each link becomes a category of its own, under the group's title. Any other
 * group is one category whose rows are its own, named by its title, or by
 * [untitledLabel] for the untitled run at the top. Groups that name a `category`
 * are one entry together, never a hub. [order] lists the labels the column puts
 * first, in that order. A link the renderer
 * fulfils natively ([nativeIds]) is not a screen, so its group stays a plain
 * category.
 */
internal fun settingsCategories(
    groups: List<CatalogGroup>,
    untitledLabel: String,
    nativeIds: Set<String> = emptySet(),
    order: List<String> = emptyList(),
): List<SettingsCategory> = buildList<SettingsCategory> {
    for (group in groups) {
        if (group.items.isEmpty()) continue
        val named = group.category
        if (named != null) {
            val key = "category:$named"
            val at = indexOfFirst { it.key == key }
            if (at >= 0) {
                val existing = this[at]
                this[at] = existing.copy(
                    icon = existing.icon ?: group.icon ?: group.items.firstNotNullOfOrNull { it.icon },
                    groupIds = existing.groupIds + group.id,
                )
            } else {
                add(
                    SettingsCategory(
                        key = key,
                        label = named,
                        icon = group.icon ?: group.items.firstNotNullOfOrNull { it.icon },
                        sectionAbove = null,
                        groupIds = listOf(group.id),
                        link = null,
                    ),
                )
            }
            continue
        }
        val hub = group.items.all { it is NestedScreenItem && it.id !in nativeIds }
        if (hub) {
            group.items.forEachIndexed { index, item ->
                add(
                    SettingsCategory(
                        key = "link:${item.id}",
                        label = item.title,
                        icon = item.icon,
                        sectionAbove = if (index == 0) group.title else null,
                        groupIds = emptyList(),
                        link = item as NestedScreenItem,
                    ),
                )
            }
        } else {
            add(
                SettingsCategory(
                    key = "group:${group.id}",
                    label = group.title ?: untitledLabel,
                    icon = group.icon ?: group.items.firstNotNullOfOrNull { it.icon },
                    sectionAbove = null,
                    groupIds = listOf(group.id),
                    link = null,
                ),
            )
        }
    }
}.let { built ->
    // A stable sort: a label the order does not name keeps its place after the ones it does.
    if (order.isEmpty()) built else built.sortedBy { category -> order.indexOf(category.label).takeIf { it >= 0 } ?: Int.MAX_VALUE }
}

/**
 * [groups] with every link to a [CatalogScreen.merged] screen replaced by that
 * screen's own groups, appended after the group that linked it as sections of
 * their own (the first headed by the screen's title when it has none). [load]
 * builds a screen's groups (`CatalogScreen.groups` with the caller's context).
 * Only one level: a merged screen's own links stay links.
 */
internal suspend fun mergeShortScreens(
    groups: List<CatalogGroup>,
    load: suspend (CatalogScreen) -> List<CatalogGroup>,
): List<CatalogGroup> {
    fun mergedScreen(item: Any): CatalogScreen? = (item as? NestedScreenItem)?.resolve()?.takeIf { it.merged }
    if (groups.none { group -> group.items.any { mergedScreen(it) != null } }) return groups
    val out = mutableListOf<CatalogGroup>()
    for (group in groups) {
        val (merged, kept) = group.items.partition { mergedScreen(it) != null }
        if (kept.isNotEmpty()) out += group.copy(items = kept)
        for (item in merged) {
            val screen = mergedScreen(item) ?: continue
            load(screen).forEachIndexed { index, section ->
                out += section.copy(
                    id = "${screen.id}/${section.id}",
                    title = section.title ?: if (index == 0) screen.title else null,
                )
            }
        }
    }
    return out
}

/**
 * [groups] without the links that open a screen the left menu already lists as a place
 * ([placeScreenIds], the registry ids of [GamingSection.placeScreenId]), and without a group that
 * held nothing else: in a mode with a left menu a place has one way in, the menu, not a second
 * row in Settings (docs/SPEC.md "Settings layout", Droidtop/tracker#273).
 */
internal fun withoutPlaceLinks(groups: List<CatalogGroup>, placeScreenIds: Set<String>): List<CatalogGroup> {
    if (placeScreenIds.isEmpty()) return groups
    fun isPlaceLink(item: Any) = (item as? NestedScreenItem)?.registryId in placeScreenIds
    return groups.mapNotNull { group ->
        val kept = group.items.filterNot { isPlaceLink(it) }
        when {
            kept.size == group.items.size -> group
            kept.isEmpty() -> null
            else -> group.copy(items = kept)
        }
    }
}
