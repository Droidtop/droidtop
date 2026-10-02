package dev.droidtop.shell.gamepad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * Who L1/R1 belong to on the page in front (docs/SPEC.md 7j, "Gaming
 * controls", Droidtop/tracker#258).
 *
 * The shoulders step the NEAREST tab strip: a page that carries a strip
 * (PC Games' views) claims them with [OwnShoulders]; a page with none
 * leaves them to the top bar, which cycles the sections as it always did.
 * One mechanism for the whole shell: the shell's root handler asks
 * [shoulderRoute] and nothing else keeps a shoulder list of its own.
 */
internal class ShoulderStrip(val step: (Int) -> Unit)

/** The strip, if any, the page in front has claimed. Held by the shell. */
internal class ShoulderStripRegistry {
    var current: ShoulderStrip? by mutableStateOf(null)
        private set

    fun claim(strip: ShoulderStrip) {
        current = strip
    }

    /**
     * Only the claimant releases: during the shell's crossfade the page
     * that is leaving is disposed AFTER the one arriving has claimed, and
     * its release must not take the new page's strip away.
     */
    fun release(strip: ShoulderStrip) {
        if (current === strip) current = null
    }
}

internal val LocalShoulderStrips = compositionLocalOf<ShoulderStripRegistry?> { null }

/**
 * Claims L1/R1 for this page's tab strip while it is composed. [step] is
 * -1 for L1 and +1 for R1; it answers nothing, because a strip that is
 * already at its end still owns the press (the shoulder never falls
 * through to the top bar and moves the whole page out from under the
 * user).
 */
@Composable
internal fun OwnShoulders(step: (Int) -> Unit) {
    val registry = LocalShoulderStrips.current
    val latest by rememberUpdatedState(step)
    DisposableEffect(registry) {
        val strip = ShoulderStrip { latest(it) }
        registry?.claim(strip)
        onDispose { registry?.release(strip) }
    }
}

internal enum class ShoulderRoute { STRIP, TOP_BAR, NONE }

/**
 * Where an L1/R1 press goes. Over a game's detail the shoulders mean
 * nothing (the detail is drawn over the section, not part of it); else
 * a page's strip takes them; else the top bar does.
 */
internal fun shoulderRoute(stripOwned: Boolean, detailOpen: Boolean): ShoulderRoute = when {
    detailOpen -> ShoulderRoute.NONE
    stripOwned -> ShoulderRoute.STRIP
    else -> ShoulderRoute.TOP_BAR
}

/**
 * L1/R1 mean something only with a pad; on a touch phone with none
 * attached the glyphs are noise, so neither the top bar nor a strip draws
 * them there.
 */
internal fun ShellWindow.showsShoulderGlyphs(): Boolean = !touchFirst || padPresent
