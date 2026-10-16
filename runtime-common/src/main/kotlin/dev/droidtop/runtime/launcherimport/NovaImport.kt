package dev.droidtop.runtime.launcherimport

import dev.droidtop.runtime.HomeAction

/**
 * A Nova Launcher backup as an [ImportPlan] (Droidtop/tracker#564; format in the tracker's
 * coordination/standard/launcher-import-formats.md, read from Nova 8.0.18). Tolerant of versions: the grid is read from
 * `desktop_grid` ("rows x cols", maybe with " subgrid") or the older ints, the icon pack from four or five fields, and
 * every database column by presence. Pure.
 */
object NovaImport {
    private const val NOVA_FLAG_CUSTOM_ICON = 1L
    private const val NOVA_FLAG_CUSTOM_TITLE = 2L
    private const val NOVA_FLAG_SWIPE_TO_OPEN = 4L

    private val GESTURES = linkedMapOf(
        "gesture_double_tap" to ImportActions.DOUBLE_TAP,
        "gesture_swipe_down" to ImportActions.SWIPE_DOWN,
        "gesture_swipe_two_down" to ImportActions.TWO_FINGER_DOWN,
        "gesture_swipe_two_up" to ImportActions.TWO_FINGER_UP,
        "gesture_pinch_in" to ImportActions.PINCH_IN,
        "gesture_pinch_out" to ImportActions.PINCH_OUT,
        "gesture_home_button" to ImportActions.HOME_BUTTON,
    )

    /** Nova gestures droidtop has no slot for. Swipe up stays droidtop's drawer. */
    private val OTHER_GESTURES = mapOf(
        "gesture_swipe_up" to "Swipe up (it opens droidtop's app drawer)",
        "gesture_rotate_cw" to "Rotate clockwise",
        "gesture_rotate_ccw" to "Rotate anticlockwise",
        "gesture_double_tap_swipe_up" to "Double tap and swipe up",
        "gesture_double_tap_swipe_down" to "Double tap and swipe down",
        "gesture_swipe_left" to "Swipe left",
        "gesture_swipe_right" to "Swipe right",
    )

    fun plan(prefsXml: String?, db: DbTables?): ImportPlan {
        val prefs = Prefs(prefsXml?.let(PrefsXml::parse).orEmpty())
        val notes = mutableListOf<ImportNote>()
        val grid = grid(prefs)
        val dockOn = prefs.bool("dock_enable") ?: true
        val dockIcons = if (!dockOn) 0 else prefs.int("dock_grid_cols") ?: 5
        // Databases older than version 29 kept Launcher3's workspaceScreens (screen ids, ordered by screenRank).
        val pages = db?.rows("workspaceScreens").orEmpty()
            .sortedBy { Favorites.long(it, "screenRank") ?: 0L }
            .mapNotNull { Favorites.long(it, "_id") }
        val raw = db?.let { Favorites.read(it.rows("favorites"), rules(pages), notes) }.orEmpty()
        val placed = Placement.place(raw, grid ?: (4 to 5), dockIcons)
        notes += placed.notes
        val (groups, hidden) = db?.let { drawerGroups(it, notes) } ?: (emptyList<PlanDrawerGroup>() to emptySet())
        val titles = db?.rows("allapps").orEmpty().mapNotNull { row ->
            val component = IntentUri.normaliseComponent(Favorites.str(row, "componentName")) ?: return@mapNotNull null
            if (Favorites.str(row, "customIconSource") != null) notes += ImportNote(component.substringBefore('/'), "A custom icon chosen in Nova's drawer is not brought across; droidtop's icon choices are per home-screen icon")
            Favorites.str(row, "title")?.takeIf { it.isNotBlank() }?.let { component to it }
        }.toMap()
        val gestures = LinkedHashMap<String, HomeAction>()
        for ((key, slot) in GESTURES) {
            val value = prefs.string(key) ?: continue
            when (val action = action(value)) {
                null -> notes += ImportNote("Gesture: ${slot.lowercase().replace('_', ' ')}", "Nova's \"${describe(value)}\" has no equivalent in droidtop; droidtop's own setting stays")
                else -> gestures[slot] = action
            }
        }
        for ((key, name) in OTHER_GESTURES) {
            val value = prefs.string(key) ?: continue
            if (action(value) != HomeAction.Droidtop("NONE")) notes += ImportNote("Gesture: $name", "droidtop has no such gesture")
        }
        return ImportPlan(
            source = SourceLauncher.NOVA,
            grid = placed.grid.takeIf { grid != null || raw.isNotEmpty() },
            dockIcons = dockIcons.takeIf { prefs.has("dock_enable") || prefs.has("dock_grid_cols") }?.let { if (it == 0) 0 else it.coerceIn(3, 10) },
            items = placed.items,
            hiddenApps = hidden,
            drawerGroups = groups,
            iconPack = iconPack(prefs),
            labels = null,
            gestures = gestures,
            appTitles = titles,
            notes = notes,
        )
    }

    /** `desktop_grid` = "rows x cols" (with " subgrid"), or the ints of builds before 7.1. Columns x rows. */
    internal fun grid(prefs: Prefs): Pair<Int, Int>? {
        prefs.string("desktop_grid")?.let { value ->
            val match = Regex("(\\d+)\\s*x\\s*(\\d+)").find(value) ?: return@let
            val rows = match.groupValues[1].toInt()
            val cols = match.groupValues[2].toInt()
            return cols.coerceIn(Placement.MIN_GRID, Placement.MAX_GRID) to rows.coerceIn(Placement.MIN_GRID, Placement.MAX_GRID)
        }
        val cols = prefs.int("desktop_grid_cols") ?: return null
        val rows = prefs.int("desktop_grid_rows") ?: return null
        return cols.coerceIn(Placement.MIN_GRID, Placement.MAX_GRID) to rows.coerceIn(Placement.MIN_GRID, Placement.MAX_GRID)
    }

    /** `theme_icon_pack` = `name:TYPE:package:...` (fields with ':' braced); the package, "" for built-in, null for none. */
    internal fun iconPack(prefs: Prefs): String? {
        val value = prefs.string("theme_icon_pack") ?: return null
        val fields = splitFields(value)
        val type = fields.getOrNull(1)?.uppercase()
        val pkg = fields.getOrNull(2)?.trim().orEmpty()
        return if (type == "BUILTIN" || pkg.isEmpty() || pkg == "null") "" else pkg
    }

    /** Nova's `uf.a0` field list: joined with ':', a field holding ':' wrapped in braces. */
    internal fun splitFields(value: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        val field = StringBuilder()
        var depth = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '{' && field.isEmpty() && depth == 0 -> depth = 1
                c == '}' && depth == 1 -> depth = 0
                c == ':' && depth == 0 -> { out += field.toString(); field.clear() }
                else -> field.append(c)
            }
            i++
        }
        out += field.toString()
        return out
    }

    /** A gesture's or a fling's value, `<title>$<intent>`: Nova's own action, or an app to start. */
    internal fun action(value: String): HomeAction? {
        val intent = IntentUri.parse(value) ?: return null
        intent.launcherAction?.let { name -> return ImportActions.droidtop(name)?.let { HomeAction.Droidtop(it) } }
        return if (intent.isAppLaunch) HomeAction.Launch(intent.component!!) else null
    }

    private fun describe(value: String): String {
        val intent = IntentUri.parse(value)
        return intent?.launcherAction?.let(ImportActions::describe) ?: value.substringBefore('$').ifBlank { "action" }
    }

    private fun rules(pages: List<Long>) = object : Favorites.Rules {
        override val launcher = SourceLauncher.NOVA
        // No workspaceScreens table in this schema: `screen` is the page index. An old database's is a screen id.
        override fun homePage(row: Map<String, Any?>): Int? {
            val screen = Favorites.long(row, "screen") ?: return null
            if (pages.isEmpty()) return screen.toInt()
            return pages.indexOf(screen).takeIf { it >= 0 }
        }
        // The dock's page 0 is the default one; droidtop has one dock page.
        override fun dockColumn(row: Map<String, Any?>) =
            if ((Favorites.long(row, "screen") ?: 0L) == 0L) Favorites.num(row, "cellX") ?: 0.0 else null
        override fun customTitle(row: Map<String, Any?>) =
            if (flags(row) and NOVA_FLAG_CUSTOM_TITLE != 0L) Favorites.str(row, "title") else null
        override fun customIcon(row: Map<String, Any?>) =
            if (flags(row) and NOVA_FLAG_CUSTOM_ICON != 0L) Favorites.blob(row, "icon") else null
        override fun cover(row: Map<String, Any?>) = flags(row) and NOVA_FLAG_SWIPE_TO_OPEN != 0L
        override fun swipe(row: Map<String, Any?>, up: Boolean): Pair<HomeAction?, String?> {
            val value = Favorites.str(row, if (up) "flingUpIntent" else "flingDownIntent")?.takeIf { it.isNotBlank() } ?: return null to null
            val action = action(value)
            return if (action != null) action to null else null to "Nova's \"${describe(value)}\" has no equivalent in droidtop"
        }
        private fun flags(row: Map<String, Any?>) = Favorites.long(row, "novaFlags") ?: 0L
    }

    /**
     * Drawer tabs and folders from `drawer_groups` and `appgroups`, and hidden apps (`groupId` -1; a database from
     * before that move kept them at 0). A folder inside a tab (`FOLDER:<n>`, or `appgroup:<id>` in an old database) is
     * its apps.
     */
    private fun drawerGroups(db: DbTables, notes: MutableList<ImportNote>): Pair<List<PlanDrawerGroup>, Set<String>> {
        val groups = db.rows("drawer_groups")
        val members = db.rows("appgroups").groupBy { Favorites.long(it, "groupId") }
        fun apps(groupId: Long, seen: Set<Long> = emptySet()): List<String> =
            members[groupId].orEmpty().flatMap { row ->
                val component = Favorites.str(row, "component").orEmpty()
                val folder = Regex("/FOLDER:(-?\\d+)$").find(component)?.groupValues?.get(1)?.toLongOrNull()?.let { -200L - it }
                    ?: Regex("/appgroup:(-?\\d+)$").find(component)?.groupValues?.get(1)?.toLongOrNull()
                when {
                    folder != null -> if (folder in seen || folder == groupId) emptyList() else apps(folder, seen + groupId)
                    else -> listOfNotNull(IntentUri.normaliseComponent(component))
                }
            }.distinct()
        val ids = groups.mapNotNull { Favorites.long(it, "_id") }.toSet()
        val hiddenGroup = if (members.containsKey(-1L) || 0L in ids) -1L else 0L
        val hidden = apps(hiddenGroup).toSet()
        val out = groups.sortedBy { Favorites.long(it, "tabOrder") ?: Long.MAX_VALUE }.mapNotNull { row ->
            val id = Favorites.long(row, "_id") ?: return@mapNotNull null
            val name = Favorites.str(row, "title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when (Favorites.str(row, "groupType")) {
                "TAB_APP_GROUP" -> PlanDrawerGroup(name, apps(id), folder = false)
                "FOLDER_APP_GROUP" -> {
                    val hides = Favorites.long(row, "hideApps")?.let { it != 0L } ?: (Favorites.str(row, "hideApps") == "true")
                    if (!hides) notes += ImportNote("Drawer folder \"$name\"", "Its apps also stayed in Nova's main list; droidtop's drawer folders take them out of All, so it is a drawer tab instead", imported = true)
                    PlanDrawerGroup(name, apps(id), folder = hides)
                }
                else -> null
            }
        }.filter { it.apps.isNotEmpty() }
        return out to hidden
    }
}
