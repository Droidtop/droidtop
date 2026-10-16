package app.murinelauncher.widget.search

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import com.android.launcher3.R

/**
 * A web search with the provider chosen in the search bar's settings ([SearchProvider], Settings > Search bar), else
 * the system's web search. droidtop patch (Droidtop/tracker#564): the home search bar opens droidtop's one search
 * (`LauncherSearchActivity`), and the web is one row there, "Search the web for ...", which comes here. It was a
 * search box of its own, with its own history (MurineSearchBoxView, deleted): two searches for one job.
 */
object WebSearch {
    private const val TAG = "WebSearch"

    @JvmStatic
    fun open(context: Context, query: String) {
        if (query.isBlank()) return
        try {
            context.startActivity(SearchProvider.current.buildSearchIntent(context, query).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Could not open search provider", e)
            system(context, query)
        } catch (e: SecurityException) {
            // A browser without an exported activity: the system's web search instead of a crash.
            Log.w(TAG, "Search provider activity not launchable", e)
            system(context, query)
        }
    }

    private fun system(context: Context, query: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_WEB_SEARCH).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(SearchManager.QUERY, query)
                },
            )
        } catch (e: RuntimeException) {
            // ActivityNotFoundException or SecurityException: no web search can be opened.
            Log.w(TAG, "No web search activity", e)
            dev.droidtop.library.settings.Notices.flash(
                context.getString(R.string.activity_not_found),
                tone = dev.droidtop.library.settings.NoticeTone.Caution,
                context = context,
            )
        }
    }
}
