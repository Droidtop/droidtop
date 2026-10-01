package dev.droidtop.display

import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
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
 * The LIVE companion on the second screen, driven by the Gaming shell
 * while that shell is foreground on the primary display.
 *
 * This coexists with SecondaryDisplayActivity (this module); they are not
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
 * companion at all; and being a real Activity it takes input focus, which
 * this window is made NOT to (FLAG_NOT_FOCUSABLE in [onCreate]). A
 * Presentation is a focusable Dialog unless told otherwise, so that
 * property is enforced rather than assumed.
 */
class SecondScreenPresentation(outerContext: Context, display: Display) : android.app.Presentation(outerContext, display) {
    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val savedStateOwner = object : SavedStateRegistryOwner {
        val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A Presentation is an ordinary focusable Dialog window layered ABOVE
        // every activity on its display. Left focusable it takes key focus
        // from whatever is underneath it (an app launched onto this display
        // got no keyboard, Droidtop/tracker#156) and, because touching a
        // focusable window on another display moves the system's top-focused
        // display, every tap on the companion pulled the gamepad away from
        // the shell on the other screen. This surface is touch-only (no text
        // fields, the keyboard surface is a pure touch View), so it asks for
        // no focus and no input method; touch is unaffected by the flag.
        window?.addFlags(SecondScreenWindowFlags.touchOnly(includeAltFocusableIm = true))
        savedStateOwner.controller.performRestore(null)
        lifecycleOwner.registry.currentState = Lifecycle.State.CREATED

        // Which mode's second-screen role applies, read from the same
        // place :display reads it, so this live surface and the idle
        // SECONDARY_HOME surface underneath always agree about what this
        // screen is for -- and drawn through the SAME registered content
        // [dev.droidtop.display.SecondaryDisplayActivity] uses
        // ([dev.droidtop.app.SecondaryDisplayRegistrations]), rather than
        // this class hardcoding the game companion regardless of mode the
        // way it used to (owner, 2026-09-27: "it seems we use the same
        // dual screen mode for standard and gaming" -- true of this
        // class, which never branched on mode at all). Widget
        // hosting/listening is the registered content's own concern now
        // (CompanionSurfaceHost's DisposableEffect, StandardSecondScreenSurface's
        // own), not duplicated here.
        val mode = SecondaryDisplayContent.currentMode(context)
        val content = SecondaryDisplayContent.contentFor(mode)
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent {
                DisplayOrientationContent {
                    if (content != null) {
                        Box(Modifier.fillMaxSize().focusProperties { canFocus = false }) { content() }
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                    }
                }
            }
        }
        setContentView(composeView)
    }

    /**
     * One log line per touch-down, so a device check can tell "the touch
     * never reached this window" (an input-association problem, nothing in
     * droidtop can fix that) from "it arrived and the UI ignored it"
     * (Droidtop/tracker#155) with `logcat -s droidtop.SecondScreen`.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            android.util.Log.d("droidtop.SecondScreen", "Presentation touch on display ${display.displayId} at ${ev.x.toInt()},${ev.y.toInt()}")
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
        super.onStop()
    }
}
