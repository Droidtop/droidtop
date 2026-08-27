package dev.droidtop.shell.gamepad.theme

import dev.droidtop.library.LibraryEntry
import kotlin.random.Random

/**
 * Real implementation of ES-DE's `<gameselector>` element: picks
 * [gameCount] games from [entries] for other elements' own
 * `gameselectorEntry` (0-based index into the returned list) to reference.
 *
 * Only `selection=random` is implemented for real — the only value the
 * bundled DEcaffe theme actually uses (`<selection>random</selection>`).
 * ES-DE's other real modes (`similar` picks by shared genre/tag,
 * `sameSystem` restricts to the current system) would need real per-game
 * genre/tag metadata droidtop's [LibraryEntry] doesn't model — any
 * unrecognized/unsupported `selection` value falls back to random rather
 * than failing, a known, documented limitation, not silently wrong output.
 *
 * Callers are responsible for only re-invoking this when the focused
 * system actually changes (e.g. `remember(entries)` in Compose) — calling
 * it every recomposition would re-randomize the selection on every frame,
 * which is not what a real ES-DE game-preview collage does (it's stable
 * until you move to a different platform).
 */
object GameSelector {
    fun select(entries: List<LibraryEntry>, gameCount: Int, allowDuplicates: Boolean, random: Random = Random.Default): List<LibraryEntry> {
        if (entries.isEmpty() || gameCount <= 0) return emptyList()
        return if (allowDuplicates || gameCount > entries.size) {
            List(gameCount) { entries.random(random) }
        } else {
            entries.shuffled(random).take(gameCount)
        }
    }
}
