package dev.droidtop.ui.shared

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.droidtop.shell.gamepad.StatusCluster

/**
 * The shared status readout ([StatusCluster]: clock, network, battery) for a View surface in a window with no activity,
 * Standard's taskbar (Droidtop/tracker#701), as [NoticeViews] is the notice card for Standard's settings rows. It is the
 * same readout Gaming's header, the companion and the Desktop tray draw, never a second copy in Views. The window has
 * no lifecycle of its own, so the [Readout] carries one (and saved state), as `InGameOverlays.overlayView` does in
 * :app; [Readout.dispose] ends it when its window goes.
 */
object StatusViews {
    class Readout internal constructor(val view: ComposeView, private val lifecycle: LifecycleRegistry) {
        private var ink by mutableIntStateOf(0)

        internal fun inkArgb(): Int = ink

        /** Its quiet colour, the surface's text colour; it redraws only when that changes. */
        fun setInk(argb: Int) {
            ink = argb
        }

        /** Ends its lifecycle and composition; call when its window is removed. */
        fun dispose() {
            view.disposeComposition()
            lifecycle.currentState = Lifecycle.State.DESTROYED
        }
    }

    /** A readout in [context] (the window's own), whose tap runs [onClick]: the Quick Menu, as on every other surface. */
    fun create(context: Context, ink: Int, onClick: () -> Unit): Readout {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val saved = object : SavedStateRegistryOwner {
            val controller = SavedStateRegistryController.create(this)
            override val lifecycle: Lifecycle get() = owner.lifecycle
            override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        }
        saved.controller.performRestore(null)
        owner.registry.currentState = Lifecycle.State.RESUMED
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(saved)
            // The bar moves this view between rebuilt rows: it keeps its composition until disposed, not on detach.
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner))
        }
        val readout = Readout(view, owner.registry)
        readout.setInk(ink)
        view.setContent {
            StatusCluster(showBatteryPercent = true, onClick = onClick, ink = Color(readout.inkArgb()))
        }
        return readout
    }
}
