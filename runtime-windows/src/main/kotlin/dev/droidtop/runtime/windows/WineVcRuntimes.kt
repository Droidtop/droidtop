package dev.droidtop.runtime.windows

import com.winlator.core.WineRegistryEditor
import java.io.File

/**
 * The "installed" record of the Visual C++ 2015-2022 runtime in a prefix (Droidtop/tracker#899).
 *
 * Wine ships its own vcruntime140 and msvcp140 (system32 and syswow64 hold them), but not the registry record the
 * Microsoft redistributable writes, `HKLM\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\<arch>`. Unreal's
 * bootstrap and many installers read that record, not the DLLs, so an Unreal game stops at "Microsoft Visual C++
 * 2015-2022 Redistributable (x64) required" (Voyage). Where a prefix has the DLLs and no record, this writes one: the
 * values the redistributable writes, for a 2022 runtime. A record that is already there, from a real install, is kept.
 */
internal object WineVcRuntimes {
    private const val MAJOR = 14
    private const val MINOR = 40
    private const val BLD = 33810
    private const val VERSION = "v$MAJOR.$MINOR.$BLD.00"

    /**
     * The registry keys (in system.reg's own spelling, without HKLM) to write for a prefix whose system32 has the
     * 64-bit runtime ([has64]) and whose syswow64 has the 32-bit one ([has32]): each architecture's record in the
     * 64-bit and the 32-bit view, since checkers read either. Pure.
     */
    fun keysFor(has64: Boolean, has32: Boolean): List<String> = buildList {
        val archs = listOfNotNull("x64".takeIf { has64 }, "x86".takeIf { has32 })
        for (arch in archs) {
            add("Software\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\$arch")
            add("Software\\Wow6432Node\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\$arch")
        }
    }

    /** Writes the records [prefix] (a `.wine` folder) lacks; returns how many keys were written. Disk work. */
    fun ensure(prefix: File): Int {
        val windows = File(prefix, "drive_c/windows")
        val keys = keysFor(
            has64 = File(windows, "system32/vcruntime140.dll").isFile,
            has32 = File(windows, "syswow64/vcruntime140.dll").isFile,
        )
        val systemReg = File(prefix, "system.reg")
        if (keys.isEmpty() || !systemReg.isFile) return 0
        var written = 0
        for (key in keys) {
            // One editor per key: the editor remembers where the last key's parent was.
            WineRegistryEditor(systemReg).use { reg ->
                if (reg.getDwordValue(key, "Installed") == 1) return@use
                reg.setDwordValue(key, "Installed", 1)
                reg.setDwordValue(key, "Major", MAJOR)
                reg.setDwordValue(key, "Minor", MINOR)
                reg.setDwordValue(key, "Bld", BLD)
                reg.setDwordValue(key, "Rbld", 0)
                reg.setStringValue(key, "Version", VERSION)
                written++
            }
        }
        return written
    }
}
