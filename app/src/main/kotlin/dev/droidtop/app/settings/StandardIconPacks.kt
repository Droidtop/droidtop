package dev.droidtop.app.settings

import android.content.Context
import app.murinelauncher.icons.IconPackManager
import com.android.launcher3.LauncherAppState
import dev.droidtop.app.LibraryCore
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.settings.IconPacks
import kotlinx.coroutines.launch

/**
 * The icon pack choice of Standard's launcher, as the shared catalog sees it (Droidtop/tracker#508). Choosing one does
 * what Standard's own Icon pack page does (the pack's icons dropped from the cache, the launcher model reloaded), and
 * then rescans the Apps kinds, so Gaming's Apps tiles, which come from the same icon cache, follow.
 */
internal object StandardIconPacks : IconPacks.Source {
    private val scope = dev.droidtop.runtime.util.AppScopes.io

    override fun installed(context: Context): List<Pair<String, String>> =
        IconPackManager.getInstalledPacks(context).map { it.packageName to it.label.toString() }

    override fun selected(context: Context): String = IconPackManager.getSelectedPack(context)

    override fun select(context: Context, pack: String) {
        val app = context.applicationContext
        IconPackManager.setSelectedPack(app, pack)
        LauncherAppState.getInstance(app).model.forceReload()
        scope.launch { runCatching { LibraryCore.library(app).rescanNow(LibraryKinds.APPS) } }
    }
}
