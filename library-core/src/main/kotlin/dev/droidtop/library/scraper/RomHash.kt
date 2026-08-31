package dev.droidtop.library.scraper

import java.io.File
import java.security.MessageDigest

/**
 * The MD5 file digest real ES-DE's automatic scraper mode is built on —
 * its confidence metric is not string similarity but hash identity:
 * ScreenScraper is queried WITH the digest, echoes each candidate's own
 * `rommd5`, and a digest-identical response is what ES-DE logs as a
 * "perfect match" and auto-selects (GuiScraperSearch.cpp, read from its
 * real source before porting).
 *
 * [HASH_MAX_BYTES] is ES-DE's own default cap (`ScraperSearchFileHashMaxSize`,
 * 384 MiB): hashing a multi-gigabyte disc image costs real minutes for a
 * search the name lookup answers anyway, so past the cap the hash is
 * skipped — the same trade ES-DE ships.
 */
object RomHash {

    const val HASH_MAX_BYTES: Long = 384L * 1024 * 1024

    /** Lowercase-hex MD5, or null when the file is over the cap or unreadable. */
    fun md5OrNull(file: File): String? {
        val size = runCatching { file.length() }.getOrDefault(0L)
        if (size <= 0L || size > HASH_MAX_BYTES) return null
        return runCatching {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
