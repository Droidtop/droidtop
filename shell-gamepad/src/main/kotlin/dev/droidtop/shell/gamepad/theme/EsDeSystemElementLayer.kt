package dev.droidtop.shell.gamepad.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.theme.EsDeSystemSlide
import dev.droidtop.library.theme.EsDeThemeView

/**
 * Everything one system contributes to the system view's element layer:
 * its own parsed theme (a theme is parsed per system, with that system's
 * `${system.*}` variables substituted -- see `ThemeAssets.loadActiveTheme`)
 * and the two pieces of state its elements bind to.
 */
data class EsDeSystemSlot(
    val view: EsDeThemeView,
    val entries: List<LibraryEntry>,
    val systemContext: EsDeSystemContext,
)

/**
 * One half of the system view's element layer -- the elements below the
 * primary component, or the ones above it -- drawn for every system near
 * the camera and translated by its distance from it.
 *
 * This is `SystemView::renderElements` (SystemView.cpp:1565-1745) with
 * the arithmetic in [EsDeSystemSlide]. ES-DE does not cross-fade or swap
 * these: the primary component owns a continuous camera offset and each
 * system's elements are drawn at `(i - mCamOffset) * mSize` from rest, so
 * the neighbours slide in from the sides exactly as fast as the carousel
 * scrolls (:1624-1637), each clipped to its own slot (:1639-1646). At
 * rest only the system under the camera is drawn (:1572-1580).
 *
 * [camOffset] is read in the draw phase and in a derived state, never
 * during composition: the offset changes every frame, and only the set of
 * systems being drawn -- which changes once per entry -- is worth
 * recomposing for.
 */
@Composable
fun EsDeSystemElementLayer(
    layer: EsDeViewLayer,
    camOffset: State<Float>,
    systemCount: Int,
    slideHorizontal: Boolean,
    backgroundDimmed: Boolean,
    transition: EsDeTransitionContext?,
    slot: @Composable (Int) -> EsDeSystemSlot?,
) {
    val rendered by remember(systemCount) {
        derivedStateOf {
            EsDeSystemSlide.renderedIndices(
                camOffset = camOffset.value,
                systemCount = systemCount,
                animating = EsDeSystemSlide.animating(camOffset.value),
            )
        }
    }
    rendered.forEach { position ->
        // Keyed by the SLOT, not by the system in it: a slot keeps its
        // place on screen while the system inside it changes as the
        // camera moves past, which is what keeps a video or an animation
        // from restarting on every step.
        key(position) {
            // Not `?: return@key`: a non-local return out of an inline
            // composable lambda compiles to a synthetic D8 cannot name.
            val system = slot(EsDeSystemSlide.wrap(position, systemCount))
            if (system != null) {
                EsDeThemedView(
                    view = system.view,
                    items = emptyList(),
                    firstItemFocus = null,
                    modifier = Modifier.fillMaxSize(),
                    focusedSystemEntries = system.entries,
                    systemContext = system.systemContext,
                    backgroundDimmed = backgroundDimmed,
                    transition = transition,
                    layer = layer,
                    slide = { EsDeSystemSlide.displacement(position, camOffset.value) },
                    slideHorizontal = slideHorizontal,
                )
            }
        }
    }
}
