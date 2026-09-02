package dev.droidtop.library.scraper

/**
 * droidtop's own ScreenScraper application credentials.
 *
 * ScreenScraper's API identifies the calling APPLICATION, not the end user: every
 * client registers one devid/devpassword pair with them and every copy of that
 * client sends it. It is an access requirement rather than a quota tier -- the
 * pair is what lets a client talk to the API at all, anonymously or otherwise.
 *
 * The scraping quota is a property of the USER's own account (ssid/sspassword),
 * which stays theirs to supply and can be substantially larger for supporters.
 * So this pair does not raise anyone's limit; it makes the client work.
 *
 * The pair is stored XOR-scrambled against a key rather than as plain literals,
 * which is exactly what ES-DE does (Utils::String::scramble, with the credentials
 * as integer arrays in ScreenScraper.h). Be clear about what that buys: it stops
 * the credentials being harvested by anyone grepping public repositories for
 * likely-looking strings. It is not encryption, and anyone who wants them can
 * recover them from a build in a minute. That is understood and accepted here --
 * these identify droidtop to ScreenScraper, they are not a user secret.
 *
 * The transform is symmetric, so the same routine both scrambles and recovers.
 *
 * PROVENANCE, because scrambled bytes cannot answer this for a reader and a
 * previous review could not rule out the bad cases: this pair was registered
 * with ScreenScraper by droidtop's owner and supplied by them directly. It was
 * not generated, not guessed, and not taken from another client. ES-DE is cited
 * above only as the source of the scrambling TECHNIQUE, never of the values.
 * Anyone replacing these must register their own pair the same way -- through
 * ScreenScraper's forum, as a human.
 */
internal object ScreenScraperDevCredentials {

    private val KEY = intArrayOf(
        213, 233, 175, 99, 13, 53, 72, 115, 164, 9, 151, 79, 170, 233, 44, 117, 66, 39, 183,
        224, 99, 31, 165, 245
    )

    private val DEV_ID = intArrayOf(
        134, 128, 195, 10, 110, 90, 38, 18, 209, 125, 248, 34, 203, 157, 67, 27
    )

    private val DEV_PASSWORD = intArrayOf(
        128, 166, 206, 90, 111, 96, 125, 7, 192, 109, 243
    )

    val devId: String get() = unscramble(DEV_ID)

    val devPassword: String get() = unscramble(DEV_PASSWORD)

    /** Byte-wise XOR against [KEY], which is at least as long as any input. */
    private fun unscramble(scrambled: IntArray): String =
        buildString(scrambled.size) {
            scrambled.forEachIndexed { index, value -> append((value xor KEY[index]).toChar()) }
        }
}
