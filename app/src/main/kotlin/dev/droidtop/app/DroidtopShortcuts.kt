package dev.droidtop.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.search.LibraryArtwork
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.DroidtopScreens
import dev.droidtop.shell.standard.LauncherSearch
import dev.droidtop.shell.standard.ModeSwitcher
import kotlinx.coroutines.launch

/**
 * droidtop's dynamic app shortcuts (docs/SPEC.md, "Widgets on any launcher"; Droidtop/tracker#563): what a long press on
 * droidtop's icon offers on any launcher, beside the static ones in `res/xml/shortcuts.xml` (Games, Social, Downloads,
 * Updates, SPEC 7j). Rebuilt whole by [refresh]:
 * - the last few games played, newest first, each playing through [GameLaunchActivity] as a pinned game does;
 * - Search (the one search), the Quick Menu and Switch mode;
 * - Gaming and Desktop, each only while that mode is on (the gate is [Modes], read when this runs).
 * [refresh] runs at start, when a mode is switched on or off ([ModeStartup]) and after a game is launched. It reads the
 * library's in-memory index and decodes art off the main thread, in the widgets' scope.
 */
internal object DroidtopShortcuts {
    /** How many recent games come first: a launcher shows four or five shortcuts in all, the static ones among them. */
    private const val RECENT_GAMES = 3
    private const val ICON_PX = 192

    fun refresh(context: Context) {
        val app = context.applicationContext
        DroidtopWidgets.scope.launch {
            runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, build(app)) }
                .onFailure { android.util.Log.w("droidtop.Shortcuts", "Dynamic shortcuts not set", it) }
        }
    }

    private suspend fun build(context: Context): List<ShortcutInfoCompat> {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val recent = DroidtopWidgets.libraryGames(context)
            .filter { it.lastPlayedEpochMs != null && !it.hidden && !it.missing }
            .sortedByDescending { it.lastPlayedEpochMs }
            .take(RECENT_GAMES)
        val shortcuts = recent.map { game(context, it) } + fixed(context)
        // The static shortcuts count against the same cap.
        return shortcuts.take((max - STATIC_COUNT).coerceAtLeast(0)).mapIndexed { rank, it -> it.withRank(rank) }
    }

    private fun game(context: Context, entry: LibraryEntry): ShortcutInfoCompat {
        val art = (entry.iconUri ?: entry.artworkUri)?.takeIf { it.isNotBlank() }
            ?.let { LibraryArtwork.decodeSquareBitmap(context, it, ICON_PX) }
        return ShortcutInfoCompat.Builder(context, "recent:${entry.id}")
            .setShortLabel(GameNaming.displayName(entry.title))
            .setIcon(art?.let(IconCompat::createWithBitmap) ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(GameLaunchActivity.intentFor(context, entry.id))
            .setActivity(ComponentName(context, LauncherGamesActivity::class.java))
            .build()
    }

    private fun fixed(context: Context): List<ShortcutInfoCompat> {
        val pkg = context.packageName
        fun shortcut(id: String, label: String, intent: Intent) =
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(label)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .setActivity(ComponentName(context, LauncherGamesActivity::class.java))
                .build()
        fun mode(mode: Mode) = Intent(Intent.ACTION_MAIN)
            .setClassName(pkg, MainActivity::class.java.name)
            .putExtra(BackButtonMenu.EXTRA_MODE, mode.id)
        return listOfNotNull(
            shortcut("search", "Search", Intent(LauncherSearch.ACTION_SEARCH).setPackage(pkg)),
            shortcut("quick_menu", "Quick Menu", Intent(Intent.ACTION_MAIN).setClassName(pkg, DroidtopScreens.QUICK_MENU_ACTIVITY)),
            shortcut("switch_mode", "Switch mode", Intent(Intent.ACTION_MAIN).setClassName(pkg, ModeSwitcher.ACTIVITY)),
            shortcut("mode_gaming", "Gaming", mode(Mode.GAMING)).takeIf { Modes.isEnabled(Mode.GAMING) },
            shortcut("mode_desktop", "Desktop", mode(Mode.DESKTOP)).takeIf { Modes.isEnabled(Mode.DESKTOP) },
        )
    }

    private fun ShortcutInfoCompat.withRank(rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(this).setRank(rank).build()

    /** The static shortcuts in res/xml/shortcuts.xml, which count against the same cap. */
    private const val STATIC_COUNT = 4
}
