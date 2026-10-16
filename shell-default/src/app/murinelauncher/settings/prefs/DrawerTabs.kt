package app.murinelauncher.settings.prefs

import android.content.Context
import com.android.launcher3.LauncherFiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app drawer's tabs (docs/SPEC.md "Standard", Droidtop/tracker#564): groups the person names and fills
 * from an app's long-press menu, shown as a row of chips at the top of the drawer. "All" is always first and
 * is not a tab of its own. An app may be in any number of tabs; a tab holds component names, so an app that
 * is uninstalled simply stops showing.
 *
 * A tab can also be a drawer folder in All (Tab.folder): one mechanism for both, the tab being the model.
 *
 * Kept as one JSON string in the launcher's preferences, beside the hidden apps, so it is backed up with them.
 * Read once and then held in memory: the drawer filters on every rebuild.
 */
object DrawerTabs {
    private const val KEY = "drawer_tabs"

    /** [folder]: shown in All as one drawer folder holding its apps, which then leave All's list. */
    data class Tab(val name: String, val apps: Set<String>, val folder: Boolean = false)

    @Volatile
    private var cache: List<Tab>? = null

    /** The tab the drawer shows, by name; null is All. Not saved: the drawer opens on All after a restart. */
    @Volatile
    @JvmStatic
    var selected: String? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)

    @JvmStatic
    fun tabs(context: Context): List<Tab> = cache ?: load(context).also { cache = it }

    /** The apps the drawer shows now, or null for All (also when the selected tab no longer exists). */
    @JvmStatic
    fun selectedApps(context: Context): Set<String>? {
        val name = selected ?: return null
        return tabs(context).firstOrNull { it.name == name }?.apps
    }

    /** Adds a tab; false when the name is blank or taken. */
    @JvmStatic
    fun add(context: Context, name: String, firstApp: String? = null): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || tabs(context).any { it.name.equals(clean, ignoreCase = true) }) return false
        save(context, tabs(context) + Tab(clean, setOfNotNull(firstApp)))
        return true
    }

    /** Renames a tab; false when the new name is blank or taken by another. */
    @JvmStatic
    fun rename(context: Context, from: String, to: String): Boolean {
        val clean = to.trim()
        if (clean.isEmpty() || tabs(context).any { it.name != from && it.name.equals(clean, ignoreCase = true) }) return false
        save(context, tabs(context).map { if (it.name == from) it.copy(name = clean) else it })
        if (selected == from) selected = clean
        return true
    }

    @JvmStatic
    fun delete(context: Context, name: String) {
        save(context, tabs(context).filter { it.name != name })
        if (selected == name) selected = null
    }

    /** Moves a tab one place left (-1) or right (+1) in the row. */
    @JvmStatic
    fun move(context: Context, name: String, by: Int) {
        val list = tabs(context).toMutableList()
        val from = list.indexOfFirst { it.name == name }
        val to = from + by
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        save(context, list)
    }

    @JvmStatic
    fun setFolder(context: Context, name: String, folder: Boolean) {
        save(context, tabs(context).map { if (it.name == name) it.copy(folder = folder) else it })
    }

    @JvmStatic
    fun setMember(context: Context, tab: String, component: String, member: Boolean) {
        save(context, tabs(context).map { if (it.name == tab) it.copy(apps = if (member) it.apps + component else it.apps - component) else it })
    }

    private fun load(context: Context): List<Tab> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val apps = o.optJSONArray("apps") ?: JSONArray()
                Tab(o.getString("name"), (0 until apps.length()).map { apps.getString(it) }.toSet(), o.optBoolean("folder"))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, tabs: List<Tab>) {
        cache = tabs
        val array = JSONArray()
        tabs.forEach { tab -> array.put(JSONObject().put("name", tab.name).put("apps", JSONArray(tab.apps.sorted())).put("folder", tab.folder)) }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }
}
