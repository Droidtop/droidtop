package dev.droidtop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.droidtop.app.ui.PadButton

/**
 * The top-of-screen line crash-loop safe mode owes the person (docs/SPEC.md
 * 10c): droidtop crashed twice while starting, so the Gaming shell is drawn
 * without the theme, and one action draws the theme again. The stored theme
 * choice was never changed.
 *
 * The action is [PadButton], the pad-first button every other row of
 * droidtop's own chrome uses: a real focus target with the shell's
 * selection ring, pressed by the pad's A, by Enter and by a finger alike.
 * A Material button here answered no pad at all -- it never takes
 * BUTTON_A, shows focus only as a faint overlay, and cannot hold focus in
 * touch mode -- which left the one recovery action touch-only
 * (Droidtop/tracker#49). The D-pad reaches it with Up from the top row of
 * the shell's lists: the banner covers the tab bar, which stays
 * unfocusable (docs/SPEC.md 7k), so the one thing Up can find above the
 * lists is this button.
 */
@Composable
fun SafeModeBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Safe mode: droidtop crashed twice while starting, so the theme is off. Your theme choice is kept.",
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            PadButton(label = "Use the theme again", onClick = onRetry, filled = true)
        }
    }
}
