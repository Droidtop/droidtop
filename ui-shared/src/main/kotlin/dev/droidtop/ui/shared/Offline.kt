package dev.droidtop.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.runtime.systemstatus.NetworkKind
import dev.droidtop.runtime.systemstatus.SystemStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

/**
 * The one offline notice every network page shows while the device has no network (Droidtop/tracker#594): the catalog
 * screens that need it ([dev.droidtop.library.settings.CatalogScreen.needsNetwork]), and the pages drawn on their own
 * (the theme browser). Get games says the same title in its own row.
 */
object Offline {
    const val TITLE = "You're offline"
    const val BODY = "This page needs a connection. What it shows may be out of date, and its actions wait until you're back online."

    fun notice(): Notice = Notice(NoticeTone.Caution, TITLE, body = BODY)
}

/** Whether the device has no network at all, live, read off the main thread (the one status source, [SystemStatus]). */
@Composable
fun rememberOffline(): State<Boolean> {
    val context = LocalContext.current
    return produceState(false, context) {
        SystemStatus.flow(context)
            .flowOn(Dispatchers.IO)
            .collect { value = it.network == NetworkKind.NONE }
    }
}
