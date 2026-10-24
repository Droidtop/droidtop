package dev.droidtop.library

/**
 * The Gaming shell's ways to show a game's own surfaces where the person is (docs/SPEC.md 7g, "Facets in place";
 * owner, 2026-10-24, Droidtop/tracker#639: "Clicking the update should present the facet containing the
 * downloads"). A row that exists to act on one facet shows it over the screen it is on, never by navigating
 * away: an update row its update's downloads, a job row its game's page, both inside Gaming so Back returns to the
 * row. Gaming installs these while it is drawn; a screen outside Gaming, where they are null, opens the game's page
 * the way it always has. The same registered-hook shape as [LaunchDisplay.chooser]: library-core and `:app` cannot
 * reach a Compose window, so the shell leaves the hook and draws the surface itself.
 */
object GameSurfaces {
    /** Shows the downloads of [entryId]'s waiting update over the current screen; false when nothing here can. */
    @Volatile var showUpdate: ((entryId: String) -> Boolean)? = null

    /** Shows [entryId]'s game page over the current screen, inside Gaming; false when nothing here can. */
    @Volatile var showPage: ((entryId: String) -> Boolean)? = null
}
