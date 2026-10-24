package dev.droidtop.library

/**
 * The Gaming shell's way to show a game's page where the person is (docs/SPEC.md 7g, "Facets in place";
 * Droidtop/tracker#639): a job row's game, over the jobs list, inside Gaming so Back returns to the row. Gaming
 * installs it while it is drawn. The same registered-hook shape as [LaunchDisplay.chooser]: library-core and `:app`
 * cannot reach a Compose window, so the shell leaves the hook and draws the page itself. An update row needs no hook:
 * its update is a settings screen of its own, pushed in place by any host.
 */
object GameSurfaces {
    /** Shows [entryId]'s game page over the current screen, inside Gaming; false when nothing here can. */
    @Volatile var showPage: ((entryId: String) -> Boolean)? = null
}
