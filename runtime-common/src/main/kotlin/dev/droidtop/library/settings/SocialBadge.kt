package dev.droidtop.library.settings

/**
 * How many social messages are unread over every provider (docs/SPEC.md "Social", Droidtop/tracker#327), for the
 * surfaces that badge Social and cannot see the social hub. `:app` fills it from the hub.
 */
object SocialBadge {
    /** Unread messages over every provider. */
    @Volatile
    var unread: Int = 0
}
