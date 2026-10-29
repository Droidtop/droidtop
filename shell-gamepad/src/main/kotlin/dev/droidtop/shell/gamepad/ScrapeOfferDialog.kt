package dev.droidtop.shell.gamepad

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * The one-time question after the library's first walk: "Fetch box art and
 * details for your games?" (docs/SPEC.md 7h, Droidtop/tracker#174). Raised by
 * [dev.droidtop.library.scraper.ScrapeOffer.pending]; either answer is stored
 * and ends the question for good, B declines. Same panel and pad handling as
 * every other modal of the shell (docs/SPEC.md 6e).
 */
@Composable
internal fun ScrapeOfferDialog(
    onFetch: () -> Unit,
    onNotNow: () -> Unit,
) {
    OfferDialog(
        title = stringResource(R.string.scrape_offer_title),
        body = "droidtop will look your games up online and download covers, descriptions and ratings. " +
            "It runs in the background, can be paused under Downloads and installs, and carries on after a restart. " +
            "You can change this later in Settings.",
        action = "Fetch now",
        decline = "Not now",
        focusLabel = "Fetch box art offer",
        onAction = onFetch,
        onDecline = onNotNow,
    )
}
