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

    /** Opens [hit] the way the launcher's own icons do: through the platform's launcher API, for its user. */
    fun launch(context: Context, hit: AppHit) {
        context.getSystemService(LauncherApps::class.java)
            .startMainActivity(hit.component, hit.user, null, null)
    }
}
