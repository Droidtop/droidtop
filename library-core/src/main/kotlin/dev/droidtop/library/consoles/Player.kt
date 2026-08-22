package dev.droidtop.library.consoles

/**
 * A way to actually launch a [dev.droidtop.library.LibraryEntry] -- modeled
 * after Daijishō's own real "Player" concept (PlayerEntity in its
 * decompiled sources: a named launch config with an am-start-style command
 * template, matched to files by a regex), generalized here to also cover
 * droidtop's own runtime backends (JoiPlay, the GameNative-derived Wine/
 * Box64 runtime, droidtop's Linux containers) as first-class Player types
 * alongside the generic am-start one -- droidtop's whole differentiator is
 * treating every kind of "thing that runs a game" as equally first-class,
 * not just emulators.
 *
 * Not every variant is actually launchable today -- see each one's own doc
 * comment. A Player existing in this model is a statement of architecture
 * ("this is how droidtop represents that kind of launch"), not a claim
 * that it works yet.
 */
sealed interface Player {
    val id: String
    val name: String

    /**
     * Launches via a real Android [android.content.Intent] built from an
     * `am start`-style argument template (see [AmStartCommandToIntentConverter])
     * -- the one real, fully working mechanism today, and the one that
     * covers essentially every console emulator (RetroArch via its
     * documented `ROM`/`LIBRETRO` extras, or any standalone emulator app
     * with its own launch Intent convention), since droidtop doesn't need
     * to special-case each one.
     *
     * [argumentsTemplate] uses `{file.path}` as its one placeholder (kept
     * deliberately simpler than Daijishō's own token set for a first
     * version -- more placeholders (`{file.name}`, `{system.id}`, ...) are
     * a real, easy follow-up once a concrete Player actually needs one).
     */
    data class AmStart(
        override val id: String,
        override val name: String,
        val argumentsTemplate: String,
        val killPackageProcesses: Boolean = false,
        // Real, required -- every am-start command targets a real package
        // (via -n or -p), and droidtop needs this separately from parsing
        // argumentsTemplate so it can cheaply check "is this emulator even
        // installed" (PackageManager) without re-parsing the whole
        // template -- see ConsoleRomProvider.availablePlayers.
        val packageName: String,
    ) : Player

    /** Delegates to the existing, real [dev.droidtop.library.JoiPlay] integration. */
    data class JoiPlayLauncher(override val id: String = "joiplay", override val name: String = "JoiPlay") : Player

    /**
     * Delegates to `runtime-windows`'s GameNative-derived Wine/Box64
     * runtime. Not wired to a real running session yet -- same gap noted in
     * [dev.droidtop.app.DesktopSessionService]'s own doc comment (no
     * primary container image published, non-root runtime unimplemented).
     * Exists here so the data model doesn't need reshaping once it is.
     */
    data class WinePrefixLauncher(override val id: String = "wine-prefix", override val name: String = "Wine/Box64") : Player

    /**
     * Delegates to `runtime-linux-root`/`runtime-linux-noroot`. Same "not
     * wired to a real running session yet" gap as [WinePrefixLauncher].
     */
    data class LinuxContainerLauncher(override val id: String = "linux-container", override val name: String = "Linux container") : Player
}
