package dev.droidtop.shell.gamepad

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * "Your theme is ready: <name>" with "Use it" and "Later" (docs/SPEC.md 7f, Droidtop/tracker#532): raised by
 * [dev.droidtop.library.theme.ThemeOffer.pending] when a theme finished downloading while nobody was choosing one, so
 * the look of the shell never changes under the person. B is Later. Same panel and pad handling as every modal here.
 */
@Composable
internal fun ThemeReadyDialog(name: String, onUse: () -> Unit, onLater: () -> Unit) {
    OfferDialog(
        title = stringResource(R.string.theme_ready, name),
        body = null,
        action = "Use it",
        decline = "Later",
        focusLabel = "Theme ready",
        onAction = onUse,
        onDecline = onLater,
    )
}
