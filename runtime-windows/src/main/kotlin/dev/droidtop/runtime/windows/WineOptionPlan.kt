package dev.droidtop.runtime.windows

/**
 * The Wine options a prefix offers, as plain values: which ones apply to
 * which Wine build on which CPU, and what changing one does to the others.
 * Kept free of Android and gamenative types so every rule here is checked by
 * a unit test; [WineOptions] feeds it the lists and writes the result.
 *
 * The rules are the fork's own, not new ones (docs/SPEC.md 5a):
 * `BionicProgramLauncherComponent` runs an x86_64 Wine as `box64 <guest>` on
 * arm64 and directly on x86_64, and an arm64ec Wine with FEXCore for 64-bit
 * code and the prefix's `emulator` (FEXCore or Box64's WowBox64) for 32-bit
 * code; `ContainerConfigDialog` couples the emulator to the Wine build the same
 * way; VKD3D needs a DXVK of 2.1 or later (`ManifestComponentHelper.
 * buildDxvkContext`). An arm64ec Wine is ARM code, so an x86_64 device is never
 * offered one.
 */
object WineOptionPlan {

    const val FEXCORE = "FEXCore"
    const val BOX64 = "Box64"

    /** The prefix fields these options edit. */
    data class Settings(
        val wine: String,
        val emulator: String,
        val box64: String,
        val fexcore: String,
        val driver: String,
        val driverVersion: String,
        val dxwrapper: String,
        val dxvk: String,
        val vkd3d: String,
    )

    /**
     * The settings a game may choose for itself over the shared prefix, by
     * name (owner, 2026-10-02): everything but the Wine build, which belongs
     * to the prefix it boots and so needs a prefix of the game's own.
     */
    val GAME_KEYS = listOf("emulator", "box64", "fexcore", "driver", "driverVersion", "dxwrapper", "dxvk", "vkd3d")

    /** [base] with a game's own choices ([GAME_KEYS] names) laid over it; unknown names are ignored. */
    fun merge(base: Settings, choices: Map<String, String>): Settings = base.copy(
        emulator = choices["emulator"] ?: base.emulator,
        box64 = choices["box64"] ?: base.box64,
        fexcore = choices["fexcore"] ?: base.fexcore,
        driver = choices["driver"] ?: base.driver,
        driverVersion = choices["driverVersion"] ?: base.driverVersion,
        dxwrapper = choices["dxwrapper"] ?: base.dxwrapper,
        dxvk = choices["dxvk"] ?: base.dxvk,
        vkd3d = choices["vkd3d"] ?: base.vkd3d,
    )

    /** What a game chose that differs from [base], by [GAME_KEYS] name; the inverse of [merge]. */
    fun diff(base: Settings, chosen: Settings): Map<String, String> = buildMap {
        if (chosen.emulator != base.emulator) put("emulator", chosen.emulator)
        if (chosen.box64 != base.box64) put("box64", chosen.box64)
        if (chosen.fexcore != base.fexcore) put("fexcore", chosen.fexcore)
        if (chosen.driver != base.driver) put("driver", chosen.driver)
        if (chosen.driverVersion != base.driverVersion) put("driverVersion", chosen.driverVersion)
        if (chosen.dxwrapper != base.dxwrapper) put("dxwrapper", chosen.dxwrapper)
        if (chosen.dxvk != base.dxvk) put("dxvk", chosen.dxvk)
        if (chosen.vkd3d != base.vkd3d) put("vkd3d", chosen.vkd3d)
    }

    fun isArm64ec(wine: String): Boolean = wine.contains("arm64ec", ignoreCase = true)

    /** The Wine builds this CPU can run, from every build the device knows of. */
    fun wineBuilds(x86Host: Boolean, known: List<String>): List<String> =
        known.distinct().filter { !x86Host || runsOnX86Host(it) }

    /** The release a build id names ("proton-10.0-4-x86_64-1" is 10), or null when it names none. */
    fun wineMajor(wine: String): Int? =
        Regex("""^(?:proton|wine)-(\d+)""", RegexOption.IGNORE_CASE).find(wine)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Whether an x86_64 CPU runs [wine] directly. Never an ARM (arm64ec)
     * build. Nor an x86_64 one older than Wine 10: GameNative's Android builds
     * fix Wine's address space at 39 bits for box64 on arm64 phones
     * (proton-wine android/patches/x86_64/dlls_ntdll_unix_virtual_c.patch),
     * and before Wine 10 ntdll sized its page table from that fixed limit
     * while the preloader's reservation near the 47-bit top raised the limit
     * after it, so the first DLL mapped above 39 bits stops Wine on
     * "alloc_pages_vprot: assertion end <= pages_vprot_size << pages_vprot_shift"
     * (Proton 9 on the BlueStacks rig, 2026-10-03). From Wine 10 ntdll asks the
     * host how far its address space reaches (get_host_addr_space_limit) and
     * sizes the table from that answer, so the same build runs under box64
     * and directly (Proton 10.0-4 and 11.0-1 start wineboot's Windows
     * processes on the rig where Proton 9 stops at the assertion).
     */
    fun runsOnX86Host(wine: String): Boolean =
        !isArm64ec(wine) && (wineMajor(wine)?.let { it >= 10 } ?: true)

    /** The 32-bit emulators to choose from; empty when the Wine build and CPU leave no choice. */
    fun emulators(x86Host: Boolean, wine: String): List<String> =
        if (!x86Host && isArm64ec(wine)) listOf(FEXCORE, BOX64) else emptyList()

    /** Whether the FEXCore version applies: an arm64ec Wine runs its 64-bit code through FEXCore. */
    fun usesFexcore(x86Host: Boolean, wine: String): Boolean = !x86Host && isArm64ec(wine)

    /** Whether a Box64 version applies: an x86_64 Wine on arm64, or WowBox64 chosen for an arm64ec one. */
    fun usesBox64(x86Host: Boolean, settings: Settings): Boolean =
        !x86Host && (!isArm64ec(settings.wine) || settings.emulator.equals(BOX64, ignoreCase = true))

    /** Box64 versions come from WowBox64's list under an arm64ec Wine and Box64's own otherwise. */
    fun box64IsWowBox64(wine: String): Boolean = isArm64ec(wine)

    /** The driver build only matters for the Wrapper family, which loads a Turnip/Qualcomm build. */
    fun usesDriverVersion(x86Host: Boolean, driver: String): Boolean =
        !x86Host && driver.startsWith("wrapper", ignoreCase = true)

    fun usesDxvk(dxwrapper: String): Boolean = dxwrapper == "dxvk" || dxwrapper == "vkd3d"

    fun usesVkd3d(dxwrapper: String): Boolean = dxwrapper == "vkd3d"

    /**
     * A new Wine build, and what it brings with it: an x86_64 build on arm64
     * runs under Box64, so the emulator becomes Box64; moving onto an arm64ec
     * build from an x86_64 one starts at FEXCore, as GameNative does. The
     * Box64 version is kept when the new build's list has it ([box64Choices]
     * is that list) and falls back to its first entry otherwise.
     */
    fun withWine(current: Settings, wine: String, x86Host: Boolean, box64Choices: List<String>): Settings {
        if (x86Host) return current.copy(wine = wine)
        val emulator = when {
            !isArm64ec(wine) -> BOX64
            isArm64ec(current.wine) -> current.emulator
            else -> FEXCORE
        }
        val box64 = if (current.box64 in box64Choices || box64Choices.isEmpty()) current.box64 else box64Choices.first()
        return current.copy(wine = wine, emulator = emulator, box64 = box64)
    }

    /**
     * A new Direct3D translation. VKD3D drives DXVK 2.1 or later; an older
     * DXVK version moves to the first one in [dxvkChoices] that qualifies.
     */
    fun withDxwrapper(current: Settings, dxwrapper: String, dxvkChoices: List<String>): Settings {
        if (dxwrapper != "vkd3d" || atLeast(current.dxvk, 2, 1)) return current.copy(dxwrapper = dxwrapper)
        val dxvk = dxvkChoices.firstOrNull { atLeast(it, 2, 1) } ?: current.dxvk
        return current.copy(dxwrapper = dxwrapper, dxvk = dxvk)
    }

    /** The DXVK versions VKD3D can use, or every one for DXVK itself. */
    fun dxvkVersions(dxwrapper: String, all: List<String>): List<String> =
        if (dxwrapper == "vkd3d") all.filter { atLeast(it, 2, 1) } else all

    /** "dxvk", "vkd3d", "wined3d" or "cnc-ddraw" for whatever spelling a prefix carries. */
    fun dxwrapperKind(value: String): String {
        val v = value.lowercase()
        return when {
            v.startsWith("dxvk") || v.startsWith("d8vk") -> "dxvk"
            v.startsWith("vkd3d") -> "vkd3d"
            v.startsWith("cnc") -> "cnc-ddraw"
            else -> "wined3d"
        }
    }

    /** True when [version]'s first major.minor is at least [major].[minor]; "async-1.10.3" reads as 1.10. */
    fun atLeast(version: String, major: Int, minor: Int): Boolean {
        val match = Regex("(\\d+)\\.(\\d+)").find(version) ?: return false
        val (a, b) = match.destructured
        val ma = a.toInt()
        return ma > major || (ma == major && b.toInt() >= minor)
    }
}
