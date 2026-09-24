package dev.droidtop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryKinds
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
 * "Games" icon in the app drawer opens a plain grid of every game the
 * shared library holds, a tap launches it, and a long press pins it to the
 * home screen as an ordinary icon.
 *
 * Nothing here is a second library or a second launch path. The list is
 * [dev.droidtop.library.Library.backgroundScanState] for the same kinds
 * the Gaming shell's Games section reads, so with both modes on they share
 * one scan; a tap is [GameLaunchActivity.dispatch]; a pinned icon is a
 * launcher shortcut whose intent is [GameLaunchActivity.intentFor]. What
 * Gaming adds on top -- themes, scraped metadata views, the Quick Menu --
 * is deliberately absent.
 *
 * This is the package's only MAIN/LAUNCHER activity, which is also what
 * makes pinning possible at all: the platform refuses a pinned shortcut
 * from a package with no launcher activity to attribute it to.
 */
class LauncherGamesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = LibraryCore.library(applicationContext)
        // Joins the Gaming shell's scan when one is running (same key);
        // otherwise it is the ordinary non-rescan scan, which for indexed
        // providers loads the index rather than walking folders.
        library.scanInBackground(LibraryKinds.GAMES)
        val games = library.backgroundScanState(LibraryKinds.GAMES)
            .map { entries -> entries?.let { shown(it) } }
            .flowOn(Dispatchers.Default)
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                val shown by games.collectAsStateWithLifecycle(initialValue = null)
                GamesGrid(
                    games = shown,
                    onLaunch = { GameLaunchActivity.dispatch(this, it) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GamesGrid(
    games: List<LibraryEntry>?,
    onLaunch: (LibraryEntry) -> Unit,
    onPin: (LibraryEntry) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            games == null -> Message("Reading the library…")
            games.isEmpty() -> Message("No games yet. Add a games folder in droidtop's settings.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 128.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(games, key = { it.id }) { entry ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .combinedClickable(
                                onClick = { onLaunch(entry) },
                                onLongClick = { onPin(entry) },
                                onLongClickLabel = "Pin to home screen",
                            )
                            .padding(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.75f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f)),
                        ) {
                            if (!entry.artworkUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = entry.artworkUri,
                                    contentDescription = entry.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Text(
                                    entry.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.75f),
                                    modifier = Modifier.padding(8.dp).align(Alignment.Center),
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyLarge)
    }
}
