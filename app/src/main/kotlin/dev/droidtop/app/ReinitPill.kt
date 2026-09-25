package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * "Reinitialize displays," findable without a user already knowing the
 * double-tap-Home gesture or the mode switcher's own row exists
 * (docs/SPEC.md section 4c). This is the automatic half of that section's
 * design: the pill draws itself only when
 * [CompanionState.dualScreenBroken] says the addon looks broken right
 * now -- mirroring, a missing companion, or a foreign app left behind
 * after it exits -- and disappears the moment MainActivity's own
 * periodic health check (or this same tap) fixes it. A backstop, not the
 * fix: the real fix is that health check re-running orchestration on its
 * own; this is what a person sees and can act on immediately instead of
 * waiting the few seconds that takes, or noticing a mirror and having no
 * idea what to do about it.
 *
 * Deliberately drawn by [MainActivity] itself rather than by either
 * shell (`GamepadShell`'s own hint row, `DesktopShell`'s taskbar): the
 * broken state it reports is a MainActivity-level fact true in both
 * Gaming and Desktop alike, and neither shell's own chrome needs a
 * second copy of when to show it.
 */
@Composable
internal fun ReinitializeDisplaysPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val broken by CompanionState.dualScreenBroken.collectAsState()
    if (!broken) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            "Reinitialize displays",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
