package dev.droidtop.shell.gamepad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize

/**
 * How much of the window's top-right corner the floating status cluster
 * covers, margins included, measured as it is drawn; zero while it is not
 * drawn. A row along the top edge (the view strip, [ViewStrip]) ends short
 * of it and shelves with no strip start under it, so nothing of the page
 * sits beneath the clock and battery (Droidtop/tracker#292). One cluster
 * per shell, so one value.
 */
object StatusClusterRoom {
    var size by mutableStateOf(DpSize.Zero)
}
