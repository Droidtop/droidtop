package dev.droidtop.shell.standard

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.LauncherApps
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.WorkerThread
import app.lawnchair.icons.setCustomLabelForKey
import app.murinelauncher.icons.IconPackManager
import app.murinelauncher.settings.hiddenapps.HiddenAppsRepository
import app.murinelauncher.settings.prefs.DrawerTabs
import app.murinelauncher.settings.prefs.LabelVisibility
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Executors
import dev.droidtop.runtime.launcherimport.ApexImport
import dev.droidtop.runtime.launcherimport.BackupReader
import dev.droidtop.runtime.launcherimport.DbTables
import dev.droidtop.runtime.launcherimport.ImportCheck
import dev.droidtop.runtime.launcherimport.ImportPlan
import dev.droidtop.runtime.launcherimport.NovaImport
import dev.droidtop.runtime.launcherimport.PlanContainer
import dev.droidtop.runtime.launcherimport.PlanItem
import dev.droidtop.runtime.launcherimport.SourceLauncher
import java.io.File

/**
 * Brings a Nova or Apex backup into Standard (docs/SPEC.md, Launcher mode "Import from Nova or Apex";
 * Droidtop/tracker#564). [read] turns the file the person picked into a plan fitted to this device, touching nothing;
 * [apply] makes it the home screen. The parsing and the fitting are `dev.droidtop.runtime.launcherimport`, pure and
 * tested; this is the Android half: the file, the SQLite copy, what is installed, and the launcher's own stores.
 */
object LauncherImport {
    private const val TAG = "droidtop.LauncherImport"
    private const val MAX_ROWS = 20_000
    private val TABLES = listOf("favorites", "workspaceScreens", "drawer_groups", "appgroups", "allapps")

    class Read(val plan: ImportPlan, val source: SourceLauncher, val databaseVersion: Int?)

    /** Reads the backup at [uri] into a plan fitted to this device. Throws [BackupReader.NotABackup] with a reason. */
    @WorkerThread
    fun read(context: Context, uri: Uri): Read {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val files = context.contentResolver.openInputStream(uri)?.use { BackupReader.read(it, name) }
            ?: throw BackupReader.NotABackup("The file could not be opened")
        val db = files.database?.let { readDatabase(context, it) }
        // The rig's check on the formats (the format document's "Not verified"): what the file held.
        Log.i(
            TAG,
            "${files.source} backup $name: entries=${files.entries}, user_version=${db?.userVersion}, " +
                "tables=${db?.tables?.mapValues { it.value.size }}, " +
                "appgroups by groupId=${db?.rows("appgroups")?.groupingBy { it["groupId"] }?.eachCount()}",
        )
        val plan = when (files.source) {
            SourceLauncher.NOVA -> NovaImport.plan(files.prefsXml, db)
            SourceLauncher.APEX -> ApexImport.plan(files.prefsXml, db, files.drawerGroupsXml, files.swipeActionsXml)
        }
        return Read(ImportCheck.fit(plan, device(context)), files.source, db?.userVersion)
    }

    /**
     * The backup's database, copied to the cache and read only, as rows. The copy has a name of its own, so a second
     * read racing this one (a recreated screen) never writes the same file, and is removed at once. A table with more
     * than [MAX_ROWS] rows is refused rather than cut short: a layout read in part would be applied as if whole.
     */
    private fun readDatabase(context: Context, bytes: ByteArray): DbTables {
        val file = File.createTempFile("launcher_import", ".db", context.cacheDir)
        try {
            file.writeBytes(bytes)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val tables = TABLES.filter { table ->
                    db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }
                }.associateWith { table -> db.query(table, null, null, null, null, null, null, (MAX_ROWS + 1).toString()).use(::rows) }
                tables.entries.firstOrNull { it.value.size > MAX_ROWS }?.let { (table, _) ->
                    Log.w(TAG, "the backup's $table table has more than $MAX_ROWS rows")
                    throw BackupReader.NotABackup("This backup holds more than $MAX_ROWS items in one list, more than droidtop reads")
                }
                return DbTables(tables, db.version)
            }
        } catch (e: BackupReader.NotABackup) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the backup's database could not be read", e)
            throw BackupReader.NotABackup("The layout in this backup could not be read")
        } finally {
            file.delete()
        }
    }

    private fun rows(cursor: Cursor): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>(cursor.count)
        while (cursor.moveToNext()) {
            out += (0 until cursor.columnCount).associate { i ->
                cursor.getColumnName(i) to when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                    else -> null
                }
            }
        }
        return out
    }

    private fun device(context: Context): ImportCheck.Device {
        val user = Process.myUserHandle()
        val apps = context.getSystemService(LauncherApps::class.java).getActivityList(null, user)
            .associate { it.componentName.flattenToString() to it.label.toString() }
        val providers = AppWidgetManager.getInstance(context).getInstalledProvidersForProfile(user)
            .map { it.provider.flattenToString() }.toSet()
        val packs = IconPackManager.getInstalledPacks(context).map { it.packageName }.filter { it.isNotEmpty() }.toSet()
        return ImportCheck.Device(apps, providers, packs)
    }

    /**
     * Makes [plan] the home screen: the grid and dock size first, so the launcher's database is the one for that
     * grid, then every item in one model transaction in place of what was there, then the rest of the settings. The
     * launcher loads again at the end. On a background thread that is neither the main nor the model thread: it
     * waits for each in turn.
     */
    @WorkerThread
    fun apply(context: Context, plan: ImportPlan) {
        val app = context.applicationContext
        val prefs = LauncherPrefs.get(app)
        // On the main thread, where the grid's preference listener moves the device profile to the new grid before
        // this returns. put, not putSync: the file is written in the background, never on the main thread.
        Executors.MAIN_EXECUTOR.submit(Runnable {
            plan.grid?.let { (cols, rows) -> prefs.put(LauncherPrefs.GRID_WIDTH.to(cols), LauncherPrefs.GRID_HEIGHT.to(rows)) }
            plan.dockIcons?.takeIf { it > 0 }?.let { prefs.put(LauncherPrefs.DOCK_ICONS.to(it)) }
        }).get()
        val model = LauncherAppState.getInstance(app).model
        Executors.MODEL_EXECUTOR.submit(Runnable { writeItems(app, model, plan) }).get()
        applySettings(app, plan)
        // Drawer titles are the icon cache's: those apps' entries are made again.
        plan.appTitles.keys.mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.distinct()
            .forEach { model.onAppIconChanged(it, Process.myUserHandle()) }
        model.forceReload()
    }

    @WorkerThread
    private fun writeItems(context: Context, model: com.android.launcher3.LauncherModel, plan: ImportPlan) {
        val controller = model.modelDbController
        // The database for the new grid, carried over from the old one as a grid change would.
        controller.tryMigrateDB(null, model.modelDelegate)
        val profile = UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle())
        val old = controller.db.query(Favorites.TABLE_NAME, arrayOf(Favorites._ID), null, null, null, null, null).use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        }
        val written = mutableListOf<Pair<Int, PlanItem>>()
        controller.newTransaction().use { transaction ->
            controller.db.delete(Favorites.TABLE_NAME, null, null)
            for (item in plan.items) {
                val id = controller.generateNewItemId()
                val values = ContentValues().apply {
                    put(Favorites._ID, id)
                    put(Favorites.CONTAINER, if (item.container == PlanContainer.DOCK) Favorites.CONTAINER_HOTSEAT else Favorites.CONTAINER_DESKTOP)
                    // A dock item's screen is its position, as Launcher3 keeps it.
                    put(Favorites.SCREEN, if (item.container == PlanContainer.DOCK) item.x else item.page)
                    put(Favorites.CELLX, item.x)
                    put(Favorites.CELLY, item.y)
                    put(Favorites.PROFILE_ID, profile)
                    put(Favorites.RANK, 0)
                }
                when (item) {
                    is PlanItem.App -> values.app(item.component)
                    is PlanItem.Widget -> {
                        values.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPWIDGET)
                        values.put(Favorites.SPANX, item.spanX)
                        values.put(Favorites.SPANY, item.spanY)
                        values.put(Favorites.APPWIDGET_ID, -1)
                        values.put(Favorites.APPWIDGET_PROVIDER, item.provider)
                        // Launcher3's restore path: a "tap to set up" placeholder until the person binds it.
                        values.put(
                            Favorites.RESTORED,
                            LauncherAppWidgetInfo.FLAG_ID_NOT_VALID or LauncherAppWidgetInfo.FLAG_PROVIDER_NOT_READY or LauncherAppWidgetInfo.FLAG_UI_NOT_READY,
                        )
                    }
                    is PlanItem.Folder -> {
                        values.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
                        values.put(Favorites.TITLE, item.title)
                        values.put(Favorites.SPANX, 1)
                        values.put(Favorites.SPANY, 1)
                        values.put(Favorites.OPTIONS, item.options or if (item.cover) FolderInfo.FLAG_SWIPE_TO_OPEN or FolderInfo.FLAG_COVER else 0)
                    }
                }
                if (controller.insert(values) < 0) continue
                written += id to item
                if (item is PlanItem.Folder) {
                    item.children.forEachIndexed { rank, child ->
                        val childId = controller.generateNewItemId()
                        val childValues = ContentValues().apply {
                            put(Favorites._ID, childId)
                            put(Favorites.CONTAINER, id)
                            put(Favorites.SCREEN, 0)
                            put(Favorites.CELLX, rank)
                            put(Favorites.CELLY, 0)
                            put(Favorites.RANK, rank)
                            put(Favorites.PROFILE_ID, profile)
                            app(child.component)
                        }
                        if (controller.insert(childValues) >= 0) {
                            written += childId to PlanItem.App(item.container, item.page, rank, 0, child.component, child.customTitle, child.customIcon)
                        }
                    }
                }
            }
            transaction.commit()
        }
        // The old items' own titles, pictures and swipes, then the new ones'.
        HomeItemExtras.forget(context, old)
        val swipes = ArrayList<HomeItemExtras.SwipeChange>()
        for ((id, item) in written) {
            when (item) {
                is PlanItem.App -> {
                    item.customTitle?.let { HomeItemExtras.setTitle(id, it) }
                    item.customIcon?.let { if (!HomeItemExtras.setPicture(context, id, it)) Log.w(TAG, "item $id's picture is not an image") }
                    item.swipeUp?.let { swipes += HomeItemExtras.SwipeChange(id, up = true, it) }
                    item.swipeDown?.let { swipes += HomeItemExtras.SwipeChange(id, up = false, it) }
                }
                is PlanItem.Folder -> item.customIcon?.let { HomeItemExtras.setPicture(context, id, it) }
                is PlanItem.Widget -> {}
            }
        }
        // Saved once for the whole layout, not once per swipe.
        HomeItemExtras.setSwipes(context, swipes)
    }

    private fun ContentValues.app(component: String) {
        val name = ComponentName.unflattenFromString(component) ?: return
        put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
        put(Favorites.INTENT, AppInfo.makeLaunchIntent(name).toUri(0))
        put(Favorites.SPANX, 1)
        put(Favorites.SPANY, 1)
    }

    /** Everything that is not an item: gestures, hidden apps, drawer tabs and titles, icon pack, labels. */
    private fun applySettings(context: Context, plan: ImportPlan) {
        val slots = mapOf(
            "DOUBLE_TAP" to LauncherPrefs.GESTURE_DOUBLE_TAP_ACTION,
            "SWIPE_DOWN" to LauncherPrefs.GESTURE_SWIPE_DOWN_ACTION,
            "TWO_FINGER_DOWN" to LauncherPrefs.GESTURE_TWO_FINGER_DOWN_ACTION,
            "TWO_FINGER_UP" to LauncherPrefs.GESTURE_TWO_FINGER_UP_ACTION,
            "PINCH_IN" to LauncherPrefs.GESTURE_PINCH_IN_ACTION,
            "PINCH_OUT" to LauncherPrefs.GESTURE_PINCH_OUT_ACTION,
            "HOME_BUTTON" to LauncherPrefs.GESTURE_HOME_BUTTON_ACTION,
        )
        for ((slot, action) in plan.gestures) slots[slot]?.let { HomeActions.setSlot(context, it, action) }
        if (plan.hiddenApps.isNotEmpty()) {
            HiddenAppsRepository.setHiddenComponents(context, HiddenAppsRepository.getHiddenComponents(context) + plan.hiddenApps)
        }
        for (group in plan.drawerGroups) {
            DrawerTabs.add(context, group.name)
            group.apps.forEach { DrawerTabs.setMember(context, group.name, it, true) }
            DrawerTabs.setFolder(context, group.name, group.folder)
        }
        val user = Process.myUserHandle()
        for ((component, title) in plan.appTitles) {
            ComponentName.unflattenFromString(component)?.let { setCustomLabelForKey(ComponentKey(it, user).toString(), title) }
        }
        plan.iconPack?.let { IconPackManager.setSelectedPack(context, it) }
        plan.labels?.let { name ->
            LabelVisibility.entries.firstOrNull { it.name == name }?.let { LauncherPrefs.get(context).put(LauncherPrefs.LABEL_VISIBILITY, it) }
        }
    }
}
