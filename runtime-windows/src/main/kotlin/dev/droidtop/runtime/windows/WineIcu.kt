package dev.droidtop.runtime.windows

import java.io.File

/**
 * ICU 68 in a prefix, for .NET 5 and later (docs/SPEC.md 9, "ICU for .NET games").
 *
 * Wine's `icu.dll` forwards its exports to `icuuc68.dll` and `icuin68.dll`. .NET 5+ finds `icu.dll`, takes the ICU
 * globalization path and fails at start when those are missing. proton-wine-tux ships them (Proton's own fix: the
 * Unicode Consortium's ICU 68.2 builds) in `lib/wine/<arch>-windows`, which container setup copies into a new prefix
 * (`ContainerManager.extractCommonDlls`). A prefix made before that, or switched to that build, gets them here at
 * launch. When the prefix still has no ICU (a Wine build without it), the launch tells .NET to use Windows' own NLS
 * instead ([NLS_VAR]); a game's own variables, set later, still win.
 */
internal object WineIcu {
    val DLLS = listOf("icuuc68.dll", "icuin68.dll", "icudt68.dll")
    const val NLS_VAR = "DOTNET_SYSTEM_GLOBALIZATION_USENLS"

    /**
     * Copies the ICU DLLs a prefix lacks from the Wine build's [libWine] (`lib/wine`) into [windowsDir]
     * (`drive_c/windows`): the 64-bit ones (in `aarch64-windows` for an [arm64ec] build) to system32, the 32-bit ones
     * to syswow64, or to system32 for a 32-bit build ([win64] false). Never replaces a file. Returns whether every
     * target folder then has all three. Disk work.
     */
    fun ensure(libWine: File, windowsDir: File, arm64ec: Boolean, win64: Boolean): Boolean {
        val targets = if (win64) {
            listOf(File(libWine, if (arm64ec) "aarch64-windows" else "x86_64-windows") to "system32", File(libWine, "i386-windows") to "syswow64")
        } else {
            listOf(File(libWine, "i386-windows") to "system32")
        }
        var complete = true
        for ((source, folder) in targets) {
            for (name in DLLS) {
                val target = File(windowsDir, "$folder/$name")
                if (target.exists()) continue
                val from = File(source, name)
                if (!from.isFile || runCatching { from.copyTo(target) }.isFailure) complete = false
            }
        }
        return complete
    }
}
