package dev.droidtop.library.consoles

/**
 * A way to actually launch a [dev.droidtop.library.LibraryEntry] -- modeled
 * after Daijishō's own real "Player" concept (PlayerEntity in its
 * decompiled sources: a named launch config with an am-start-style command
 * template, matched to files by a regex). [AmStart] is the one kind: PC games
 * run through their own runners (`RunnerAvailability`), not a Player.
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
     * [argumentsTemplate] uses `{file.path}` as its one placeholder, simpler
     * than Daijishō's own token set.
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
        /**
         * The same launch with plain paths, for an emulator that can open a file
         * path itself: used instead of [argumentsTemplate] when the emulator holds
         * storage access ([launchTemplateFor]). Optional data in the
         * players database (`storagePathTemplate`); null leaves the preset on
         * [argumentsTemplate] always. A plain path never depends on how much of a
         * content URI the emulator's own file helper reads (Droidtop/tracker#270).
         */
        val storagePathTemplate: String? = null,
    ) : Player
}
