package dev.droidtop.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.shell.gamepad.KeyboardTargets
import dev.droidtop.shell.gamepad.input.SlabCaps
import org.pocketworkstation.pckeyboard.Slab
import org.pocketworkstation.pckeyboard.SlabSink
import org.pocketworkstation.pckeyboard.SlabView

/**
 * The keyboard as part of the companion's own layout (owner, 2026-10-16: "by default, it's PART of the companion,,
 * especially if droidtop is set as the keyboard"; docs/SPEC.md 4c): while a field on the other screen asks for keys
 * ([request], KeyboardTargets.companion), the slab docks under the tab the person is on, and its keys go to that
 * field's sink. B or Done ends the request (the companion's Hide), as the field losing focus does.
 */
@Composable
internal fun CompanionDockedSlab(request: KeyboardTargets.Request) {
    val scheme = MaterialTheme.colorScheme
    val colors = SlabView.Colors(
        ground = scheme.surface.toArgb(),
        key = scheme.surfaceVariant.toArgb(),
        keyFocused = scheme.secondaryContainer.toArgb(),
        text = scheme.onSurface.toArgb(),
        muted = scheme.onSurfaceVariant.toArgb(),
        accent = scheme.primary.toArgb(),
    )
    // A new request is a new entry: the slab opens fresh (on g, letters, Shift off) for it.
    key(request) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                SlabView(context, Slab.Kind.PLAIN, Slab.Form.FIELD, Slab.EnterPurpose.DONE, colors, SlabCaps::label, { output ->
                    SlabSink.deliver(output, request.sink, onClose = { KeyboardTargets.hideCompanion() })
                })
            },
        )
    }
}
