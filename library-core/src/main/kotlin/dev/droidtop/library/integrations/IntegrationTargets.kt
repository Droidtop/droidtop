package dev.droidtop.library.integrations

import dev.droidtop.library.LibraryEntry
import java.io.File

/**
 * One real file hanging off a [LibraryEntry] that an
 * [IntegrationCapability.OPEN_WITH] integration can be pointed at.
 *
 * Deliberately a closed, computed list rather than "any file the entry
 * touches". The rule is: a target is a file droidtop genuinely HAS and
 * genuinely has no viewer of its own for. That is what makes "open with"
 * an addition rather than a substitution -- droidtop is not handing away
 * a job it already does, it is opening a door that was previously
 * painted on.
 *
 * What is NOT a target, on purpose:
 *  - **The game/ROM file itself.** Deciding which app opens a ROM is
 *    already owned, end to end, by the player database
 *    (`players-database.json`, per-system and per-game defaults with a
 *    real override UI). An `open_with` integration that could also claim
 *    the launch path would be a second mechanism for a job that already
 *    has one, and a silent way to take over a behaviour the user
 *    configured elsewhere. See docs/SPEC.md section 12.
 *  - **Scraped artwork.** droidtop has its own media viewer for that
 *    (shell-gamepad's `MediaViewer`), so handing it to another app would
 *    be substitution, not addition.
 */
data class OpenWithTarget(
    /** What the user sees, e.g. "Manual". */
    val label: String,
    val file: File,
)

/**
 * The openable files [entry] actually has, in a stable order.
 *
 * Both of these are real, already-modeled ES-DE media slots that
 * droidtop resolves at scan time and then does nothing with beyond a
 * badge: [LibraryEntry.manualUri] is a scraped PDF with no reader
 * anywhere in droidtop at all, and [LibraryEntry.videoUri] is a preview
 * clip that only ever auto-plays muted inside a theme element -- neither
 * can be opened, paused, or read properly today.
 *
 * A path that no longer exists is dropped rather than offered: media is
 * resolved at scan time and the card outlives the file.
 */
fun openWithTargetsFor(entry: LibraryEntry): List<OpenWithTarget> = buildList {
    entry.manualUri?.let { path -> File(path).takeIf { it.isFile }?.let { add(OpenWithTarget("Manual", it)) } }
    entry.videoUri?.let { path -> File(path).takeIf { it.isFile }?.let { add(OpenWithTarget("Video", it)) } }
}

/**
 * What one "open with" chip says.
 *
 * The file's name is only added when the entry genuinely has more than
 * one openable file: with a single target, "Open in my PDF reader:
 * Manual" is noise, and with two, a bare label would give the user two
 * identical-looking chips that do different things.
 */
fun openWithChipLabel(integration: Integration, target: OpenWithTarget, targetCount: Int): String =
    if (targetCount <= 1) integration.label else "${integration.label}: ${target.label}"
