package dev.droidtop.runtime.tasks

/**
 * Which files droidtop asks a privileged helper to read or write for it: files in shared storage only
 * (`/storage/...`, `/sdcard/...`), among them another app's `Android/data` folder, which the helper's shell user
 * reaches and droidtop cannot since Android 11. That is where emulators keep their config and BIOS folders
 * (docs/SPEC.md "Emulator setup helper"). Never app-private `/data`, never a relative part (`.`, `..`, an empty
 * one), so a helper is only ever asked about a file that was named on screen. Pure, for tests.
 */
object ElevatedFiles {
    /** Config files are small; a reply past this is refused rather than half-read. */
    const val MAX_READ_BYTES = 4 * 1024 * 1024

    /** A BIOS image or a system file; a PS2 BIOS is 4 MiB, the largest firmware sets are a few tens of MiB. */
    const val MAX_WRITE_BYTES = 64 * 1024 * 1024

    private val ROOTS = listOf("/storage/", "/sdcard/")

    fun allowed(path: String): Boolean {
        if (path.length > 1024 || path.any { it == '\u0000' || it.isISOControl() }) return false
        if (ROOTS.none { path.startsWith(it) } || path.endsWith("/")) return false
        return path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }
    }
}
