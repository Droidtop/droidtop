package dev.droidtop.shell.standard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.UserHandle
import android.os.UserManager
import com.android.launcher3.LauncherAppState
import com.android.launcher3.pm.UserCache
import com.android.launcher3.search.StringMatcherUtility
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Standard mode's one search (docs/SPEC.md 12a "Launcher search"). The
 * drawer's search field and a hardware key typed in the drawer do not
 * filter the drawer in place any more: they open droidtop's search screen,
 * the shared library search (one list over apps, games and the
 * download sources) with Recommendations, which the `:app` module draws
 * (`dev.droidtop.app.LauncherSearchActivity`). `:shell-default` cannot
 * depend on `:app`, so the screen is named by an action, and this object
 * is the launcher's half of the seam: opening it, and answering it for the
 * installed apps, which only the launcher's own model knows (icons, user
 * profiles, quiet private space).
 */
object LauncherSearch {
    /** The action `LauncherSearchActivity` answers; [EXTRA_TEXT] is what to start with. */
    const val ACTION_SEARCH = "dev.droidtop.app.action.SEARCH"
    const val EXTRA_TEXT = "dev.droidtop.app.extra.SEARCH_TEXT"

    /** An installed app that matches a query: what a result row needs. */
    class AppHit(
        val title: String,
        val icon: Bitmap,
        val component: ComponentName,
        val user: UserHandle,
    )

    @JvmStatic
    @JvmOverloads
    fun open(context: Context, initialText: String = "") {
        val intent = Intent(ACTION_SEARCH)
            .setPackage(context.packageName)
            .putExtra(EXTRA_TEXT, initialText)
        // From the launcher (an Activity) the search joins its task, so Back returns to it.
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * The installed apps whose title matches every word of [query], in the
     * drawer's own order and with the drawer's own rule for words, off the
     * main thread on the launcher model's executor. An app of a quiet
     * private space is not offered, as in the drawer.
     */
    suspend fun findApps(context: Context, query: String): List<AppHit> =
        suspendCancellableCoroutine { continuation ->
            val app = context.applicationContext
            LauncherAppState.getInstance(app).model.enqueueModelUpdateTask { _, _, apps ->
                val lower = query.lowercase()
                val matcher = StringMatcherUtility.StringMatcher.getInstance()
                val users = app.getSystemService(UserManager::class.java)
                val userCache = UserCache.INSTANCE.get(app)
                val hits = ArrayList<AppHit>()
                for (info in apps.data) {
                    val component = info.targetComponent ?: continue
                    if (userCache.getUserInfo(info.user).isPrivate && users.isQuietModeEnabled(info.user)) continue
                    if (StringMatcherUtility.matches(lower, info.title.toString(), matcher)) {
                        hits.add(AppHit(info.title.toString(), info.bitmap.icon, component, info.user))
                    }
                }
                if (continuation.isActive) continuation.resume(hits)
            }
        }

    /** An app shortcut that matches a query (Droidtop/tracker#704): its label, its app's name, and its icon if any. */
    class ShortcutHit(
        val title: String,
        val appTitle: String,
        val icon: Bitmap?,
        val info: android.content.pm.ShortcutInfo,
    )

    @Volatile private var shortcutCache: Pair<Long, List<ShortcutHit>>? = null
    private const val SHORTCUT_CACHE_MS = 30_000L
    private const val MAX_SHORTCUTS = 6

    /**
     * The apps' shortcuts (static, dynamic and pinned, every profile) whose label matches every word of [query], with
     * the drawer's own rule for words. Android serves them only to the default launcher
     * ([LauncherApps.hasShortcutHostPermission]), so nothing is offered when droidtop is not Home. The list is read
     * once and kept [SHORTCUT_CACHE_MS], so typing filters it in memory. Off the main thread.
     */
    fun findShortcuts(context: Context, query: String): List<ShortcutHit> {
        if (query.isBlank()) return emptyList()
        val all = allShortcuts(context.applicationContext)
        val lower = query.lowercase()
        val matcher = StringMatcherUtility.StringMatcher.getInstance()
        return all.filter { StringMatcherUtility.matches(lower, it.title, matcher) }.take(MAX_SHORTCUTS)
    }

    private fun allShortcuts(app: Context): List<ShortcutHit> {
        val now = android.os.SystemClock.elapsedRealtime()
        shortcutCache?.takeIf { now - it.first < SHORTCUT_CACHE_MS }?.let { return it.second }
        val apps = app.getSystemService(LauncherApps::class.java)
        if (!runCatching { apps.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()
        val query = LauncherApps.ShortcutQuery().setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
        )
        val density = app.resources.displayMetrics.densityDpi
        val pm = app.packageManager
        val hits = app.getSystemService(UserManager::class.java).userProfiles.flatMap { user ->
            runCatching { apps.getShortcuts(query, user).orEmpty() }.getOrDefault(emptyList())
        }.filter { it.isEnabled }.distinctBy { it.`package` to it.id }.map { info ->
            val label = (info.shortLabel ?: info.longLabel)?.toString().orEmpty()
            val appTitle = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.`package`, 0)).toString() }.getOrDefault(info.`package`)
            val icon = runCatching { apps.getShortcutIconDrawable(info, density)?.let(::toBitmap) }.getOrNull()
            ShortcutHit(label, appTitle, icon, info)
        }.filter { it.title.isNotBlank() }
        shortcutCache = now to hits
        return hits
    }

    private fun toBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    /** Starts [hit] through the platform's launcher API, as a long-pressed icon's shortcut does. */
    fun launch(context: Context, hit: ShortcutHit) {
        runCatching {
            context.getSystemService(LauncherApps::class.java).startShortcut(hit.info, null, null)
        }
    }

    /** Opens [hit] the way the launcher's own icons do: through the platform's launcher API, for its user. */
    fun launch(context: Context, hit: AppHit) {
        context.getSystemService(LauncherApps::class.java)
            .startMainActivity(hit.component, hit.user, null, null)
    }
}
