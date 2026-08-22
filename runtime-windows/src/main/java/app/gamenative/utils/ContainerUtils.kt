package app.gamenative.utils

/**
 * Minimal, real compatibility shim for the one real thing forked-in
 * `com.winlator.xenvironment.ImageFsInstaller.clearSteamDllMarkers` calls on
 * `app.gamenative.utils.ContainerUtils` (confirmed via reading its actual
 * usage this session): `ContainerUtils.INSTANCE.
 * extractGameIdFromContainerId(containerId)`. Upstream's real `ContainerUtils`
 * is 1400+ lines deeply coupled to `SteamService`/`PrefManager`/Amazon/Epic/
 * GOG service layers -- not forked in, same reasoning as the `SteamService`
 * shim. Real upstream parses a numeric suffix out of the container id (e.g.
 * strips a "(1)" dedup suffix, then finds the trailing numeric run); ported
 * faithfully here since it's a small, pure string operation with no service
 * coupling, rather than stubbed to a constant. Every real call site
 * (`clearSteamDllMarkers`) already wraps this in a try/catch and only uses
 * the id to look up Steam-specific state droidtop doesn't have yet, so an
 * honest 0 for anything that doesn't parse is safe.
 */
object ContainerUtils {
    fun extractGameIdFromContainerId(containerId: String): Int {
        val idWithoutSuffix = if (containerId.contains("(")) {
            containerId.substringBefore("(")
        } else {
            containerId
        }
        val digits = idWithoutSuffix.takeLastWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }
}
