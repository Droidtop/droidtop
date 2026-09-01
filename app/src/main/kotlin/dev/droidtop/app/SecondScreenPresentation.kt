package dev.droidtop.app

import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The LIVE companion on the second screen, driven by the Handheld shell
 * while that shell is foreground on the primary display.
 *
 * This coexists with `:display`'s `SecondaryDisplayActivity`; they are not
 * alternatives, and an earlier pass deleting this one in favour of that
 * one was a mistake, made on a premise that turned out to be false.
 *
 * - `SECONDARY_HOME` (the Activity) is the IDLE surface: what the second
 *   screen shows when droidtop is not foreground -- at boot, after a game
 *   on that display exits, when the user is in another app. The platform
 *   places and re-places it.
 * - `Presentation` (this class) is the ACTIVE surface: a window owned by
 *   the foreground shell, so companion content tracks shell focus without
 *   a second Activity competing for input focus, and without depending on
 *   droidtop holding the home role at all.
 *
 * iiSU carries exactly this split -- its dex references
 * `Landroid/app/Presentation` alongside its `SecondaryHomeActivity`, with
 * state for which is in use (`usingPresentation`,
 * `usingPresentationExternal`, `retainPresentation`,
 * `temporaryPresentationDisabled`). See docs/SPEC.md section 4c.
 *
 * Two concrete things the Activity alone cannot do, which is why this is
 * back: a `SECONDARY_HOME` activity is only placed when droidtop holds
 * the HOME role, so droidtop used as an ordinary app would show no
 * companion at all; and being a real Activity it can take input focus,
 * which this window never does.
 */
class SecondScreenPresentation(outerContext: Context, display: Display) : android.app.Presentation(outerContext, display) {
    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    // Its own host, so widgets the user already added render on this
    // display too. Listening starts/stops with the presentation.
    private val widgetManager = android.appwidget.AppWidgetManager.getInstance(outerContext)
    private val widgetHost = CompanionWidgets.host(outerContext)
    private val widgetIds = CompanionWidgetPrefs.widgetIds(outerContext)

    private val savedStateOwner = object : SavedStateRegistryOwner {
        val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedStateOwner.controller.performRestore(null)
        lifecycleOwner.registry.currentState = Lifecycle.State.CREATED

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent {
                val entry by CompanionState.focusedEntry.collectAsState()
                // darkTheme = true: an ambient always-dark companion
                // surface (black ground is the design, like an idle
                // screen) -- see DroidtopTheme's own doc comment.
                dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                    // The SAME surface CompanionActivity hosts, not a
                    // reduced copy -- see CompanionSurface's doc comment
                    // for the bug that divergence caused.
                    CompanionSurface(
                        entry = entry,
                        widgetIds = widgetIds,
                        widgetManager = widgetManager,
                        widgetHost = widgetHost,
                        // No add/remove controls: binding a widget needs an
                        // Activity result and a Presentation cannot receive
                        // one. Already-added widgets still render here.
                        controls = null,
                    )
                }
            }
        }
        setContentView(composeView)
    }

    override fun onStart() {
        super.onStart()
        // Without this a hosted widget renders once and then never
        // updates -- no clock tick, no now-playing change.
        CompanionWidgets.startListening(context)
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        CompanionWidgets.stopListening()
        lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
        super.onStop()
    }
}
