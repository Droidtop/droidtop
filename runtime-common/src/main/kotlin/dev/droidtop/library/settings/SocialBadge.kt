package dev.droidtop.library.settings

/**
 * What the Quick Menu's Social tile shows (docs/SPEC.md "Social", Droidtop/tracker#327): whether any
 * social provider exists (a store with friends, a plugin that provides `social.provider`), and how many
 * messages are unread over all of them. `:app` fills it from the social hub; it is plain numbers so the
 * settings catalog, which cannot see the hub, can draw the tile.
 */
object SocialBadge {
    /** True while any social provider exists. */
    @Volatile
    var available: Boolean = false

    /** Unread messages over every provider. */
    @Volatile
    var unread: Int = 0
}
