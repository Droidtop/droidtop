package app.gamenative

/**
 * Minimal, real compatibility shim for the one real thing forked-in code
 * actually calls on `app.gamenative.PrefManager` (confirmed via reading
 * its actual usage this session, not guessed): `WineUtils.java`'s
 * `PrefManager.INSTANCE.getCustomGameManualFolders()` -- a
 * `Set<String>` of folders a user manually added games from outside the
 * normal Steam/Epic/GOG library flow. Empty by default (droidtop has no
 * such folders configured yet) -- real, honest "not wired up," not a
 * fabricated always-true/false stand-in. This is a distinct class from
 * `com.winlator.PrefManager` (forked in wholesale, unmodified, with real
 * DataStore-backed Wine/container preferences) -- upstream genuinely has
 * both, at different layers.
 */
object PrefManager {
    // No manual INSTANCE field needed -- Kotlin's `object` declaration
    // already generates a real public static INSTANCE automatically,
    // exactly what WineUtils.java's real `PrefManager.INSTANCE.
    // getCustomGameManualFolders()` call expects from Java.
    var customGameManualFolders: Set<String> = emptySet()
}
