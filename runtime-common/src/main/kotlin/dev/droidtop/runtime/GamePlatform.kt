package dev.droidtop.runtime

/**
 * Mirrors Steam's own per-depot OS tagging (gamenative's own `OS` enum,
 * `vendor/gamenative/app/src/main/java/app/gamenative/enums/OS.kt` — not a
 * dependency here since `:runtime-windows` builds from *ported* gamenative
 * code rather than a Gradle dependency on its module, see that module's
 * own build.gradle.kts). droidtop keeps its own copy so this package
 * doesn't need to depend on gamenative's module just for an enum.
 */
enum class GameDepotPlatform { WINDOWS, LINUX, MACOS }

/**
 * One depot option for a game, as much as droidtop needs to pick a launch
 * path (docs/SPEC.md §5a) — deliberately not a full port of gamenative's
 * `DepotInfo`, just the fields the selection decision below needs.
 *
 * [cpuArchKnown] flags the real, still-open gap noted in §5a: gamenative's
 * own depot metadata (`OSArch`) only distinguishes 32-bit vs. 64-bit, not
 * CPU family (x86 vs. ARM) — so even a [GameDepotPlatform.LINUX] depot's
 * *binary* is x86/x86-64 needing translation (§3c) in the common case, and
 * telling that apart from a genuinely ARM64-native Linux build isn't
 * something droidtop can determine from standard Steam depot metadata
 * alone. `false` here means "assume x86, needs FEX/Box64" — not "unusable."
 */
data class GameDepotOption(
    val depotId: Int,
    val platform: GameDepotPlatform,
    val cpuArchKnown: Boolean = false,
)

/**
 * §5a's actual selection rule: prefer a native Linux depot — runs as a
 * normal process inside a droidtop container (§3), no Wine involved at
 * all — over Windows (which needs Wine + Box64/FEX translation, §5/§5a).
 * macOS isn't a droidtop target and is never picked.
 *
 * This is strictly the *decision*; wiring it into gamenative's actual
 * depot-download/launch pipeline is real, separate integration work not
 * done here. Partially unblocked as of gamenative-tux `78a19b61`
 * ("Make native-Linux depot download actually reachable, as a real
 * opt-in"): `vendor/gamenative/.../service/SteamService.kt`'s
 * `filterForDownloadableDepots` no longer unconditionally rejects a
 * Linux depot — it now takes a `preferLinux` flag (default `false`,
 * user-opt-in via a new `PrefManager.preferLinuxDepots` setting), so a
 * Linux depot CAN be downloaded, just not auto-selected yet. Droidtop
 * still doesn't call gamenative with that flag set based on this
 * function's own decision — that real wiring is still separate,
 * not-yet-done work.
 */
fun selectBestDepot(options: List<GameDepotOption>): GameDepotOption? {
    if (options.isEmpty()) return null
    return options.firstOrNull { it.platform == GameDepotPlatform.LINUX }
        ?: options.firstOrNull { it.platform == GameDepotPlatform.WINDOWS }
}

/**
 * Launches a native Linux game binary directly inside a container — no
 * Wine, no [dev.droidtop.runtime.windows.WineSession] — the strictly-
 * better path §5a describes for when [selectBestDepot] picks a
 * [GameDepotPlatform.LINUX] depot. If the binary is x86/x86-64 (the common
 * case per [GameDepotOption.cpuArchKnown]'s own doc), translation is
 * expected to happen transparently via FEX's `binfmt_misc` registration
 * (§3c) — NOT implemented yet, so an x86 Linux binary won't actually run
 * via this class until that lands; a genuinely ARM64-native binary would
 * work today.
 */
class NativeLinuxGameSession(
    val container: Container,
    val runtime: ContainerRuntime,
) {
    suspend fun launch(executablePath: String, args: List<String> = emptyList()): ContainerExecResult =
        runtime.exec(
            container = container,
            command = listOf(executablePath) + args,
            env = mapOf("WAYLAND_DISPLAY" to "wayland-0"),
        )
}
