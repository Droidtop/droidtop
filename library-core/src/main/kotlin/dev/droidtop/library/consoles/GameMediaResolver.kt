package dev.droidtop.library.consoles

import java.io.File

/**
 * Real per-game artwork, read from ES-DE's own `downloaded_media` layout
 * (`<gamesRoot>/downloaded_media/<systemId>/<mediaType>/<romBaseName>.<ext>`,
 * matched by filename rather than any gamelist.xml tag -- confirmed via
 * ES-DE's own real docs: "ES-DE does not use tags inside the gamelist.xml
 * files to find game media but instead matches the media to the names of
 * the game/ROM files"). Media-type folder names themselves (screenshot,
 * cover, titlescreen, marquee, physicalmedia, fanart) are confirmed
 * against the bundled DEcaffe theme's own real `<imageType>` values
 * (theme.xml), not guessed.
 *
 * droidtop doesn't scrape media itself (no scraper exists yet -- explicit,
 * separately-scoped future work) -- this only *reads* real media a user's
 * existing ES-DE (or Skyscraper, or any other scraper writing the same
 * real layout) already produced, the same "read, don't re-invent, sync
 * data-level with ES-DE's own conventions" principle SPEC.md's §7b already
 * establishes for gamelist.xml import.
 */
object GameMediaResolver {
    // Priority order: a screenshot/cover reads better as a game-list/
    // detail-view backdrop than a marquee (a logo-shaped strip) or
    // physicalmedia (a boxed cartridge photo, often busy/low-contrast).
    private val MEDIA_FOLDERS = listOf("screenshots", "titlescreens", "covers", "marquees", "fanart", "physicalmedia")
    private val EXTENSIONS = listOf("png", "jpg", "jpeg")

    fun findArtwork(gamesRoot: File, systemId: String, romBaseName: String): String? {
        val mediaRoot = File(File(gamesRoot, "downloaded_media"), systemId)
        for (folder in MEDIA_FOLDERS) {
            for (ext in EXTENSIONS) {
                val candidate = File(File(mediaRoot, folder), "$romBaseName.$ext")
                if (candidate.isFile) return candidate.absolutePath
            }
        }
        return null
    }
}
