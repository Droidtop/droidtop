package org.pocketworkstation.pckeyboard

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PadSurfaceVisibilityTest {
    @Test fun aVisiblePanelKeepsPadOwnershipAfterItsKeyGridFadesOut() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val deck = FrameLayout(activity)
            val keys = View(activity)
            deck.addView(keys)
            activity.setContentView(deck)
            assertTrue(PadKeys.surfaceShown(keys, deck, panelOpen = false))

            // ToolsDeck.toggle hides the grid with INVISIBLE while the overlay and parent stay visible.
            keys.visibility = View.INVISIBLE
            assertFalse(keys.isShown)
            assertTrue(PadKeys.surfaceShown(keys, deck, panelOpen = true))
            assertFalse(PadKeys.surfaceShown(keys, deck, panelOpen = false))

            // A still-recorded overlay must not capture gamepad input after the entire IME goes away.
            deck.visibility = View.GONE
            assertFalse(PadKeys.surfaceShown(keys, deck, panelOpen = true))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
