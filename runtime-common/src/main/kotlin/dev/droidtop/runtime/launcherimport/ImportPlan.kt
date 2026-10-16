package dev.droidtop.runtime.launcherimport

/** Which launcher a backup came from. */
enum class SourceLauncher(val label: String) { NOVA("Nova Launcher"), APEX("Apex Launcher") }

/**
 * A launcher's database as rows, by table: what the Android side reads from the backup's SQLite file (read only)
 * and hands to the pure importers, so they are tested without SQLite. [userVersion] is SQLite's `user_version`.
 * Columns are read by presence, never by version, because versions differ in which columns exist.
 */
class DbTables(val tables: Map<String, List<Map<String, Any?>>>, val userVersion: Int) {
    fun rows(table: String): List<Map<String, Any?>> = tables[table].orEmpty()
}

/**
 * What a home-screen action does, in droidtop's terms: one of Standard's gesture actions by its enum name
 * (`OPEN_DRAWER`, `LOCK_SCREEN`, ...), or launching an app ([component], flattened).
 */
sealed class PlanAction {
    data class Droidtop(val action: String) : PlanAction()
    data class Launch(val component: String) : PlanAction()
}

/** Where an item sits: on a home page ([page], from 0 in order) or in the dock ([page] unused), at a cell. */
enum class PlanContainer { HOME, DOCK }

/** One thing on the home screen or in the dock. Positions are droidtop cells, after [Placement]. */
sealed class PlanItem {
    abstract val container: PlanContainer
    abstract val page: Int
    abstract val x: Int
    abstract val y: Int

    data class App(
        override val container: PlanContainer,
        override val page: Int,
        override val x: Int,
        override val y: Int,
        val component: String,
        /** A title the person typed for this one icon; null keeps the app's own. */
        val customTitle: String? = null,
        /** A picture the person gave this one icon (PNG or similar bytes); null keeps the app's. */
        val customIcon: ByteArray? = null,
        val swipeUp: PlanAction? = null,
        val swipeDown: PlanAction? = null,
    ) : PlanItem()

    data class Folder(
        override val container: PlanContainer,
        override val page: Int,
        override val x: Int,
        override val y: Int,
        val title: String,
        /** Launcher3 folder options to keep (manual name 8, multi-page animation 4). */
        val options: Int,
        /** Nova's swipe-to-open folder: a tap starts the first app, the icon shows it. */
        val cover: Boolean,
        /** The folder's own picture, in place of its preview. */
        val customIcon: ByteArray?,
        /** Its apps, in order. */
        val children: List<FolderChild>,
        val customTitle: Boolean = true,
    ) : PlanItem()

    data class Widget(
        override val container: PlanContainer,
        override val page: Int,
        override val x: Int,
        override val y: Int,
        val spanX: Int,
        val spanY: Int,
        /** The provider, flattened. Restored as Launcher3's "tap to set up" placeholder. */
        val provider: String,
    ) : PlanItem()
}

data class FolderChild(val component: String, val customTitle: String? = null, val customIcon: ByteArray? = null)

/** A drawer tab or drawer folder ([folder]: its apps leave All), Standard's DrawerTabs. */
data class PlanDrawerGroup(val name: String, val apps: List<String>, val folder: Boolean)

/**
 * A line of the import summary, in plain words: something in the backup that was not brought across
 * ([imported] false), or that came across changed (a moved icon, a doubled grid). Nothing is dropped silently.
 */
data class ImportNote(val what: String, val reason: String, val imported: Boolean = false)

/**
 * Everything an import would do, built by [NovaImport] or [ApexImport] from the backup alone, before anything on the
 * device changes. The Android side checks it against what is installed (apps, icon pack, widget providers), shows the
 * summary and applies it. Pure data.
 */
data class ImportPlan(
    val source: SourceLauncher,
    /** Columns x rows of the home grid; null keeps droidtop's. */
    val grid: Pair<Int, Int>?,
    /** Icons in the dock; null keeps droidtop's. 0 means the dock is off: nothing is placed in it. */
    val dockIcons: Int?,
    val items: List<PlanItem>,
    val hiddenApps: Set<String>,
    val drawerGroups: List<PlanDrawerGroup>,
    /** The icon pack's package; "" for the system icons; null to leave droidtop's choice. */
    val iconPack: String?,
    /** Standard's LabelVisibility by name; null keeps droidtop's. */
    val labels: String?,
    /** Standard's gesture slots by name (`DOUBLE_TAP`, `HOME_BUTTON`, ...). */
    val gestures: Map<String, PlanAction>,
    /** Per-app titles from the drawer (Nova `allapps`), by flattened component. */
    val appTitles: Map<String, String>,
    val notes: List<ImportNote>,
)
