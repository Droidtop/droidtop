package app.murinelauncher.settings.prefs

import androidx.annotation.StringRes
import com.android.launcher3.R

/**
 * droidtop patch (Droidtop/tracker#564): the app drawer's order, Nova/Apex-style. Most used counts launches made from
 * the launcher (`RecentAppsStore.launchCounts`), the most launched first and the rest alphabetically after them.
 */
enum class DrawerSort(@StringRes val title: Int) {
    ALPHABETICAL(R.string.drawer_sort_alphabetical),
    MOST_USED(R.string.drawer_sort_most_used),
}
