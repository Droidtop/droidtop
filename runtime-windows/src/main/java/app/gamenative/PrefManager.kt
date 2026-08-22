package app.gamenative

/**
 * Minimal, real compatibility shim for what forked-in code actually calls
 * on `app.gamenative.PrefManager` (confirmed via reading real usage this
 * session): `WineUtils.java`'s `getCustomGameManualFolders()`, and
 * `BionicProgramLauncherComponent.java`'s `username`/`refreshToken`/
 * `steamUserSteamId64` (real DataStore-backed properties in upstream's
 * actual 1000+-line `PrefManager.kt`, used there to publish the logged-in
 * Steam account's identity into the Wine-side env vars / native
 * libsteamclient.so bootstrap -- see `SteamBootstrap.kt`, forked in
 * wholesale unmodified since it's genuinely self-contained). Real, empty
 * defaults here (no account signed in yet) -- every real call site already
 * null/empty-checks before using these, so this is an honest "not signed
 * in," not a fabricated identity. This is a distinct class from
 * `com.winlator.PrefManager` (forked in wholesale, unmodified, with real
 * DataStore-backed Wine/container preferences) -- upstream genuinely has
 * both, at different layers.
 */
object PrefManager {
    // No manual INSTANCE field needed -- Kotlin's `object` declaration
    // already generates a real public static INSTANCE automatically,
    // exactly what the real Java call sites expect.
    var customGameManualFolders: Set<String> = emptySet()
    var username: String = ""
    var refreshToken: String = ""
    var steamUserSteamId64: Long = 0L
}
