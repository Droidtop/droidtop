package dev.droidtop.library.settings

/**
 * What the Quick Menu's Friends tile shows (docs/SPEC.md 7g "Stores" and 7j "Places",
 * Droidtop/tracker#313): whether any signed-in store has friends, and how many messages
 * are unread. `:app` fills it from the stores' social state; it is plain numbers so the
 * settings catalog, which cannot see the stores, can draw the tile.
 */
object FriendsBadge {
    /** True while a store with friends is signed in. */
    @Volatile
    var available: Boolean = false

    @Volatile
    var unread: Int = 0
}
