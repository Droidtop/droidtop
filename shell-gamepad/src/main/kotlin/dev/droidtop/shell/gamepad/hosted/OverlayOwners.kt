package dev.droidtop.shell.gamepad.hosted

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices

/**
 * What an activity gives the shell's screens, for a window that has none: the in-game overlays, drawn in an
 * accessibility or draw-over-apps window over another app (docs/SPEC.md "In-game overlays", Droidtop/tracker#788).
 * Every screen the overlays show is the shell's own, and some ask for what only an activity has: a catalog page
 * registers its file and folder pickers (`rememberLauncherForActivityResult`), several screens handle Back
 * (`BackHandler`). Without these the Quick Menu over RetroArch crashed droidtop on its Downloads tab ("No
 * ActivityResultRegistryOwner").
 *
 * A picker cannot answer a window over a game: it would open under the overlay, and the overlay is not there to take
 * the answer. So a launch here is answered "cancelled" at once, and the person is told to pick from droidtop itself.
 * Back has no system key in an overlay (B closes it); the dispatcher is there for the screens that register handlers.
 */
@Composable
fun ProvideOverlayOwners(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val resultOwner = remember {
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    android.util.Log.i("droidtop.InGameOverlay", "a picker was asked for over a game; answered cancelled")
                    Notices.flash(
                        "Choosing a file works from droidtop itself, not over a game",
                        tone = NoticeTone.Caution,
                        context = context,
                    )
                    dispatchResult(requestCode, android.app.Activity.RESULT_CANCELED, null)
                }
            }
        }
    }
    val backOwner = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val lifecycle = lifecycleOwner.lifecycle
        }
    }
    CompositionLocalProvider(
        LocalActivityResultRegistryOwner provides resultOwner,
        LocalOnBackPressedDispatcherOwner provides backOwner,
        content = content,
    )
}
