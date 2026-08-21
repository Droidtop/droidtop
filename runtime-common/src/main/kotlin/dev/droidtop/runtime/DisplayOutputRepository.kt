package dev.droidtop.runtime

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Live view of every [DisplayOutput] Android currently reports, via
 * [DisplayManager.DisplayListener] — not a one-shot snapshot at launch.
 * "Multi-display fixing" (per direction) means reacting to displays
 * actually appearing/disappearing at runtime (the second screen/an
 * external lapdock monitor being connected or removed), not assuming a
 * fixed set decided once at Activity start.
 *
 * [DisplayOutputKind] is assigned from [Display.getDisplayId] alone
 * ([Display.DEFAULT_DISPLAY] = PRIMARY_SCREEN, anything else = SECOND_SCREEN
 * for now — no real signal yet distinguishes "the Retroid's second screen"
 * from "an external lapdock monitor" among non-default displays; both are
 * folded into SECOND_SCREEN until real hardware differentiates them,
 * `DisplayOutputKind.EXTERNAL` stays unused). This is deliberately just
 * *enumeration*, not physical position — see [DualScreenCoordinator] for
 * why `DisplayOutputKind` is only ever a starting guess, never trusted as
 * "which one is physically upper/lower" (§4).
 */
class DisplayOutputRepository(private val context: Context) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun observe(): Flow<List<DisplayOutput>> = callbackFlow {
        fun emitCurrent() {
            trySend(currentOutputs())
        }

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = emitCurrent()
            override fun onDisplayRemoved(displayId: Int) = emitCurrent()
            override fun onDisplayChanged(displayId: Int) = emitCurrent()
        }
        displayManager.registerDisplayListener(listener, null)
        emitCurrent()

        awaitClose { displayManager.unregisterDisplayListener(listener) }
    }

    private fun currentOutputs(): List<DisplayOutput> =
        displayManager.displays
            .filter { it.state == Display.STATE_ON }
            .map { display ->
                @Suppress("DEPRECATION") // Display.getRealSize is deprecated API 30+ (WindowMetrics instead) but works down to minSdk 26 without an Activity/Window context, which a Presentation-target Display doesn't have yet
                val point = android.graphics.Point().also { display.getRealSize(it) }
                DisplayOutput(
                    id = display.displayId.toString(),
                    androidDisplayId = display.displayId,
                    kind = if (display.displayId == Display.DEFAULT_DISPLAY) DisplayOutputKind.PRIMARY_SCREEN else DisplayOutputKind.SECOND_SCREEN,
                    widthPx = point.x,
                    heightPx = point.y,
                )
            }
}
