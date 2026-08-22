package dev.droidtop.library.romdetect

/**
 * Real, forked from Lemuroid (see SerialScanner.kt's own doc comment) --
 * combined here from several of its own separate one-function files
 * (FileUtils.kt, ByteArrayKt.kt, StringsUtils.kt, IntKt.kt) purely to
 * reduce file count for this narrower port; each function's own real body
 * is unmodified.
 */
fun extractExtension(fileName: String): String = fileName.substringAfterLast(".", "").lowercase()

fun Int.kiloBytes(): Int = this * 1000

fun Int.megaBytes(): Int = this.kiloBytes() * 1000

fun String.startsWithAny(strings: Collection<String>) = strings.any { this.startsWith(it) }

/** Return the index at which the array was found or -1. */
fun ByteArray.indexOf(byteArray: ByteArray): Int {
    if (byteArray.isEmpty()) {
        return 0
    }

    outer@ for (i in 0 until this.size - byteArray.size + 1) {
        for (j in byteArray.indices) {
            if (this[i + j] != byteArray[j]) {
                continue@outer
            }
        }
        return i
    }
    return -1
}
