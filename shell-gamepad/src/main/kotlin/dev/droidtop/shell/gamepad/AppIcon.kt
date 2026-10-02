package dev.droidtop.shell.gamepad

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.droidtop.library.DrawableBitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * An installed app's launcher icon, loaded off the main thread and kept for the process (a handful of
 * running apps, so the cache stays small). Draws nothing until the icon is ready, which keeps a row's
 * height steady. Used by the task manager's lists on the Quick Menu and the companion.
 */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val context = LocalContext.current.applicationContext
    val icon by produceState(AppIcons.cache[packageName], packageName) {
        value = AppIcons.cache[packageName] ?: withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                DrawableBitmaps.render(drawable, AppIcons.SIZE_PX, AppIcons.SIZE_PX).asImageBitmap()
                    .also { AppIcons.cache[packageName] = it }
            } catch (e: Exception) {
                null
            }
        }
    }
    icon?.let { Image(bitmap = it, contentDescription = null, modifier = modifier.size(size)) }
}

private object AppIcons {
    const val SIZE_PX = 96
    val cache = ConcurrentHashMap<String, ImageBitmap>()
}
