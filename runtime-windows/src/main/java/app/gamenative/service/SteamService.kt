package app.gamenative.service

/**
 * Minimal, real compatibility shim for the two things forked-in
 * `com.winlator.xenvironment.components.*ProgramLauncherComponent` and
 * `com.winlator.xenvironment.ImageFsInstaller` actually call on
 * `app.gamenative.service.SteamService` (confirmed via reading their real
 * usage this session): the `keepAlive` companion var (Java call site
 * `SteamService.setKeepAlive(...)`, matching upstream's real `@JvmStatic`
 * property) and `getAppDirPath(gameId)` (Java call site `SteamService.
 * Companion.getAppDirPath(...)`, which needs a real nested `companion
 * object` -- not a plain Kotlin `object`, which has no `Companion` member
 * for Java to see -- matching upstream's real `class SteamService :
 * Service() { companion object { ... } }` shape). Upstream's real `SteamService` is a huge
 * (3600+ line) JavaSteam/Hilt-backed foreground service -- not forked in,
 * per this session's direction to keep droidtop's own code separate from
 * gamenative's app-level Steam/library layer. `getAppDirPath` returns an
 * empty string here (droidtop has no Steam-library game-directory mapping
 * yet); every real call site that uses it (`ImageFsInstaller.
 * clearSteamDllMarkers`) is wrapped in a try/catch that already tolerates
 * failures, so this is an honest "not wired up yet," not a silent lie.
 */
class SteamService {
    companion object {
        @JvmStatic
        var keepAlive: Boolean = false

        @JvmStatic
        fun getAppDirPath(gameId: Int): String = ""
    }
}
