package dev.droidtop.runtime.launcherimport

import dev.droidtop.runtime.HomeAction

/**
 * An Apex Launcher backup as an [ImportPlan] (Droidtop/tracker#564; format in the tracker's
 * coordination/standard/launcher-import-formats.md, read from Apex 4.9.20): the Launcher3 database (pages from
 * `workspaceScreens`), the preferences, and Pro's drawer folders and per-icon swipe actions when the backup has them.
 * Columns and keys are read by presence, so other versions read alike. Pure.
 */
object ApexImport {
    private val GESTURES = linkedMapOf(
        "desktop_double_tap_action" to ImportActions.DOUBLE_TAP,
        "swipe_down_action" to ImportActions.SWIPE_DOWN,
        "two_finger_swipe_down_action" to ImportActions.TWO_FINGER_DOWN,
        "two_finger_swipe_up_action" to ImportActions.TWO_FINGER_UP,
        "pinch_in_action" to ImportActions.PINCH_IN,
        "pinch_out_action" to ImportActions.PINCH_OUT,
        "home_key_action" to ImportActions.HOME_BUTTON,
    )

    private val OTHER_GESTURES = mapOf(
        "swipe_up_action" to "Swipe up (it opens droidtop's app drawer)",
        "double_tap_swipe_up" to "Double tap and swipe up",
        "double_tap_swipe_down" to "Double tap and swipe down",
    )

    fun plan(prefsXml: String?, db: DbTables?, drawerGroupsXml: String? = null, swipeActionsXml: String? = null): ImportPlan {
        val prefs = Prefs(prefsXml?.let(PrefsXml::parse).orEmpty())
        val swipes = Prefs(swipeActionsXml?.let(PrefsXml::parse).orEmpty())
        val notes = mutableListOf<ImportNote>()
        val cols = prefs.int("home_screen_grid_columns")
        val rows = prefs.int("home_screen_grid_rows")
        val grid = if (cols != null && rows != null) cols.coerceIn(Placement.MIN_GRID, Placement.MAX_GRID) to rows.coerceIn(Placement.MIN_GRID, Placement.MAX_GRID) else null
        val dockOn = prefs.bool("show_dock") ?: true
        val dockIcons = if (!dockOn) 0 else prefs.int("number_of_dock_icons") ?: 5
        if ((prefs.int("number_of_dock_pages") ?: 1) > 1) notes += ImportNote("Dock pages", "Apex had ${prefs.int("number_of_dock_pages")} dock pages; droidtop's dock has one, so only the first page's icons come across")
        val pages = db?.rows("workspaceScreens").orEmpty()
            .sortedBy { Favorites.long(it, "screenRank") ?: 0L }
            .mapNotNull { Favorites.long(it, "_id") }
        val raw = db?.let { Favorites.read(it.rows("favorites"), rules(pages, swipes), notes) }.orEmpty()
        val placed = Placement.place(raw, grid ?: (4 to 5), dockIcons)
        notes += placed.notes
        val gestures = LinkedHashMap<String, HomeAction>()
        for ((key, slot) in GESTURES) {
            if (!prefs.has(key)) continue
            when (val action = gestureAction(prefs, key)) {
                null -> notes += ImportNote("Gesture: ${slot.lowercase().replace('_', ' ')}", "Apex's \"${ImportActions.describe(prefs.string(key).orEmpty())}\" has no equivalent in droidtop; droidtop's own setting stays")
                else -> gestures[slot] = action
            }
        }
        for ((key, name) in OTHER_GESTURES) {
            val value = prefs.string(key) ?: continue
            if (!value.equals("NONE", ignoreCase = true)) notes += ImportNote("Gesture: $name", "droidtop has no such gesture")
        }
        val iconPack = prefs.string("theme_iconpack_pkg")?.let { if (it.isBlank() || it == "default") "" else it }
        return ImportPlan(
            source = SourceLauncher.APEX,
            grid = placed.grid.takeIf { grid != null || raw.isNotEmpty() },
            dockIcons = dockIcons.takeIf { prefs.has("show_dock") || prefs.has("number_of_dock_icons") }?.let { if (it == 0) 0 else it.coerceIn(3, 10) },
            items = placed.items,
            hiddenApps = prefs.stringSet("hidden_apps").orEmpty().mapNotNull(IntentUri::normaliseComponent).toSet(),
            drawerGroups = drawerGroups(drawerGroupsXml, notes),
            iconPack = iconPack,
            labels = labels(prefs),
            gestures = gestures,
            appTitles = emptyMap(),
            notes = notes,
        )
    }

    /** Apex's home and drawer label switches as Standard's LabelVisibility name. */
    internal fun labels(prefs: Prefs): String? {
        val home = prefs.bool("home_screen_show_labels")
        val drawer = prefs.bool("drawer_label_icons")
        if (home == null && drawer == null) return null
        return when {
            home != false && drawer != false -> "AUTO"
            home == false && drawer == false -> "NEVER_FULL"
            home == false -> "NEVER"
            else -> "NEVER_DRAWER"
        }
    }

    /** A gesture key's action name, or its app (`<key>_app`, `<key>_intent`) when it launches one. */
    private fun gestureAction(prefs: Prefs, key: String): HomeAction? {
        appTarget(prefs, key)?.let { return HomeAction.Launch(it) }
        return ImportActions.droidtop(prefs.string(key))?.let { HomeAction.Droidtop(it) }
    }

    private fun appTarget(prefs: Prefs, key: String): String? =
        listOf("${key}_app", "${key}_intent", "${key}_shortcut_intent").firstNotNullOfOrNull { k ->
            IntentUri.parse(prefs.string(k))?.takeIf { it.isAppLaunch }?.component
        }

    private fun rules(pages: List<Long>, swipes: Prefs) = object : Favorites.Rules {
        override val launcher = SourceLauncher.APEX
        // `screen` is a workspaceScreens id; page order is screenRank. Without the table, the id is the order.
        override fun homePage(row: Map<String, Any?>): Int? {
            val screen = Favorites.long(row, "screen") ?: return null
            if (pages.isEmpty()) return screen.toInt()
            return pages.indexOf(screen).takeIf { it >= 0 }
        }
        // The dock's `screen` is page base + position; the default page's base is 0.
        override fun dockColumn(row: Map<String, Any?>): Double? {
            val screen = Favorites.long(row, "screen") ?: return null
            return if (screen in 0L..99L) screen.toDouble() else null
        }
        // Apex keeps the item's title whether or not the person typed it; the importer compares it with the app's.
        override fun customTitle(row: Map<String, Any?>) = Favorites.str(row, "title")
        // An app's icon blob in this schema is not known to be a custom one; a folder's is its own picture.
        override fun customIcon(row: Map<String, Any?>) =
            if (Favorites.long(row, "itemType") == Favorites.TYPE_FOLDER) Favorites.blob(row, "icon") else null
        override fun cover(row: Map<String, Any?>) = false
        override fun swipe(row: Map<String, Any?>, up: Boolean): Pair<HomeAction?, String?> {
            val id = Favorites.long(row, "_id") ?: return null to null
            val dir = if (up) "swipe_up" else "swipe_down"
            val app = listOf("${dir}_app_$id", "${dir}_intent_$id", "${dir}_shortcut_intent_$id").firstNotNullOfOrNull { k ->
                IntentUri.parse(swipes.string(k))?.takeIf { it.isAppLaunch }?.component
            }
            if (app != null) return HomeAction.Launch(app) to null
            val name = swipes.string("${dir}_$id") ?: return null to null
            if (name.equals("NONE", ignoreCase = true)) return null to null
            val action = ImportActions.droidtop(name)
            return if (action != null) HomeAction.Droidtop(action) to null else null to "Apex's \"${ImportActions.describe(name)}\" has no equivalent in droidtop"
        }
    }

    /** Pro's drawer folders: `all_folders`, then `folder_name_<id>` and `folder_items_<id>` per folder. */
    private fun drawerGroups(xml: String?, notes: MutableList<ImportNote>): List<PlanDrawerGroup> {
        val prefs = Prefs(xml?.let(PrefsXml::parse) ?: return emptyList())
        val ids = prefs.string("all_folders")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return ids.mapNotNull { id ->
            val name = prefs.string("folder_name_$id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val apps = prefs.string("folder_items_$id").orEmpty().split(';').mapNotNull(IntentUri::normaliseComponent).distinct()
            if (apps.isEmpty()) return@mapNotNull null
            val keep = prefs.bool("folder_keep_in_tab_$id") == true
            if (keep) notes += ImportNote("Drawer folder \"$name\"", "Its apps also stayed in Apex's main list; droidtop's drawer folders take them out of All, so it is a drawer tab instead", imported = true)
            PlanDrawerGroup(name, apps, folder = !keep)
        }
    }
}
