package dev.droidtop.library.scraper

/**
 * What a picture is for, the roles Steam's library and SteamGridDB (and its Decky plugin, decky-steamgriddb) give art
 * (docs/SPEC.md 7h, "Artwork roles", Droidtop/tracker#585): each role has its own shape and its own place, and a
 * picture is shown only in a slot of its shape, never squashed or cropped into another.
 *
 * [mediaFolder] is the ES-DE `downloaded_media` folder it is filed in ([PcMediaLayout.mediaFile]): a capsule is
 * ES-DE's cover, a hero and a wide capsule are its fan art (droidtop's landscape slot, `heroPath`), a logo its
 * marquee; an icon has no ES-DE type and goes to droidtop's own `icons`.
 */
enum class ArtworkRole(val mediaFolder: String) {
    /** Box art, Steam's library capsule: 600x900, 2:3 portrait. Square box art (a CD case) is shown here too. */
    CAPSULE("covers"),

    /** Steam's wide capsule (920x430, about 2.1:1) and a site's 16:9 banner: the landscape slot. */
    WIDE_CAPSULE("fanart"),

    /** The banner behind a game's page: 1920x620, about 3.1:1. */
    HERO("fanart"),

    /** A transparent logo drawn over the hero. */
    LOGO("marquees"),

    /** A small square for lists. */
    ICON("icons"),
}

object ArtworkRoles {
    /** Up to this width-to-height, a picture is box art: portrait, or square as a CD case's is. */
    private const val BOX_MAX = 1.25

    /** From this width-to-height, a landscape picture is a hero, not a wide capsule. */
    private const val HERO_MIN = 2.6

    /**
     * The role a picture offered as a game's cover has by its real shape: box art stays a [ArtworkRole.CAPSULE]; a
     * landscape one (a forum thread's banner) is a [ArtworkRole.WIDE_CAPSULE] or, very wide, a [ArtworkRole.HERO].
     * Null when the size is unknown. Pure, for the tests.
     */
    fun ofShape(width: Int, height: Int): ArtworkRole? {
        if (width <= 0 || height <= 0) return null
        val ratio = width.toDouble() / height
        return when {
            ratio <= BOX_MAX -> ArtworkRole.CAPSULE
            ratio < HERO_MIN -> ArtworkRole.WIDE_CAPSULE
            else -> ArtworkRole.HERO
        }
    }
}
