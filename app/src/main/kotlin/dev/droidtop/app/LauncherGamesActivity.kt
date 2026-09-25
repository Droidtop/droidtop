package dev.droidtop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.scanFollowingGamesRoots
import dev.droidtop.shell.gamepad.LauncherGamesScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Launcher mode's view of the library (docs/SPEC.md, "Launcher mode"): the
 * "Games" icon in the app drawer opens a grid of every game the shared
 * library holds, drawn by the shell's own [LauncherGamesScreen] so it is
 * recognisably droidtop's; A or a tap plays, Y or a long press pins the
 * game to the home screen as an ordinary icon.
 *
 * Nothing here is a second library or a second launch path. The list is
 * [dev.droidtop.library.Library.backgroundScanState] for the same kinds
 * the Gaming shell's Games section reads, so with both modes on they share
 * one scan; a tap is [GameLaunchActivity.dispatch]; a pinned icon is a
 * launcher shortcut whose intent is [GameLaunchActivity.intentFor]. What
 * Gaming adds on top -- themes, scraped metadata views, the Quick Menu --
 * is deliberately absent.
 *
 * It is the package's one ALWAYS-enabled MAIN/LAUNCHER activity, which
 * is also what makes pinning possible at all: the platform refuses a
 * pinned shortcut from a package with no launcher activity to attribute
 * it to, and the other one (the OpenShells icon into the shells) goes
 * away with Gaming and Desktop.
 */
class LauncherGamesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = LibraryCore.library(applicationContext)
        // The same scan the Gaming shell's Games section runs, following
        // the games roots as they change (Library.scanFollowingGamesRoots),
        // so with both modes on there is one scan, and a folder added from
        // this screen's Game folders is walked at once. While the screen
        // is started: a grid nobody can see does not keep a walk going.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                library.scanFollowingGamesRoots(applicationContext, LibraryKinds.GAMES)
            }
        }
        val games = library.backgroundScanState(LibraryKinds.GAMES)
            .map { entries -> entries?.let { shown(it) } }
            .flowOn(Dispatchers.Default)
        // droidtop's own chrome owns the whole window, as the shells do:
        // the black ground runs under the status bar instead of a stock
        // app's bar colour, and the content keeps clear of the bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                val shown by games.collectAsStateWithLifecycle(initialValue = null)
                LauncherGamesScreen(
                    games = shown,
                    onPlay = { GameLaunchActivity.dispatch(this, it) },
                    onPin = { pin(applicationContext, it) },
                )
            }
        }
    }

    private companion object {
        /** Off the process scope: an icon being built must not die with the screen that asked. */
        val pinScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Pixel edge of a pinned icon's bitmap; the launcher scales it to its own icon size. */
        const val ICON_PX = 192

        /** What the grid lists: games that can be launched, by name. */
        fun shown(entries: List<LibraryEntry>): List<LibraryEntry> =
            entries.filter { !it.hidden && !it.missing }
                .sortedBy { (it.sortName ?: it.title).lowercase() }

        /**
         * Asks the home screen to pin [entry]. The home screen shows its
         * own confirmation (Launcher3's AddItemActivity in Launcher mode)
         * and places the icon; a launcher that cannot pin says so here.
         */
        fun pin(context: Context, entry: LibraryEntry) {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                Toast.makeText(context, "This home screen cannot pin games", Toast.LENGTH_LONG).show()
                return
            }
            pinScope.launch {
                val icon = artworkIcon(context, entry)
                    ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
                val shortcut = ShortcutInfoCompat.Builder(context, "game:${entry.id}")
                    .setShortLabel(entry.title)
                    .setIcon(icon)
                    .setIntent(GameLaunchActivity.intentFor(context, entry.id))
                    // Attributed to this activity by name: left unset, the
                    // platform picks one of the package's launcher
                    // activities, and the other one (OpenShells) is
                    // disabled whenever Gaming and Desktop are both off.
                    .setActivity(android.content.ComponentName(context, LauncherGamesActivity::class.java))
                    .build()
                val asked = runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }
                    .onFailure { android.util.Log.w("droidtop.LauncherGames", "Pin of ${entry.title} refused", it) }
                    .getOrDefault(false)
                if (!asked) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "${entry.title} could not be pinned", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        /**
         * The entry's own artwork, cropped square and scaled down, or null.
         * Only local art: a remote cover would mean a network fetch to
         * build an icon, and the app icon is an honest stand-in.
         */
        fun artworkIcon(context: Context, entry: LibraryEntry): IconCompat? {
            val art = entry.artworkUri?.takeIf { it.isNotBlank() } ?: return null
            val uri = Uri.parse(art)
            val open: () -> java.io.InputStream? = when (uri.scheme) {
                null, "file" -> { -> File(uri.path ?: art).inputStream() }
                "content", "android.resource" -> { -> context.contentResolver.openInputStream(uri) }
                else -> return null
            }
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_PX) sample *= 2
                val decoded = open()?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return null
                val edge = minOf(decoded.width, decoded.height)
                val square = Bitmap.createBitmap(
                    decoded, (decoded.width - edge) / 2, (decoded.height - edge) / 2, edge, edge,
                )
                IconCompat.createWithBitmap(Bitmap.createScaledBitmap(square, ICON_PX, ICON_PX, true))
            }.getOrNull()
        }
    }
}
