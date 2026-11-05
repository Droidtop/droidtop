package dev.droidtop.runtime.windows

import java.io.File

/**
 * Repairs XAudio2's COM registration in a prefix (Droidtop/tracker#898).
 *
 * The prefix template droidtop starts from (GameNative's prefix pack) registers XAudio2 2.7's class, in the 32-bit
 * view, to `xapofx1_5.dll`. Wine's xapofx DLLs register no COM class and export no `DllGetClassObject` (they build
 * `xaudio_classes.idl` for its header only; each `xaudio2_N` registers its own classes through
 * `xaudio_register.idl`), so every 32-bit game that creates XAudio2 through COM fails: "XAudio2Create() error" in
 * Date A Live: Rio Reincarnation. A prefix Wine builds itself (`wineboot`, as Proton's default prefix is made) points
 * each class at its own `xaudio2_N.dll`; this puts any of the 32 XAudio2 classes (XAudio2, XAudio2_Debug,
 * AudioVolumeMeter and AudioReverb for versions 2.0 to 2.7, the GUIDs of Wine's xaudio2.idl and xaudio2fx.idl) that
 * names an xapofx DLL back on it, in both views. Nothing else in the registry is touched.
 */
internal object WineXAudioRegistry {
    /** Each XAudio2 class GUID (lower case) and the version, N in `xaudio2_N.dll`, that registers it. */
    val CLASSES: Map<String, Int> = listOf(
        // XAudio2, XAudio2_Debug, AudioVolumeMeter, AudioReverb
        listOf("fac23f48-31f5-45a8-b49b-5225d61401aa", "fac23f48-31f5-45a8-b49b-5225d61401db", "c0c56f46-29b1-44e9-9939-a32ce86867e2", "6f6ea3a9-2cf5-41cf-91c1-2170b1540063"),
        listOf("e21a7345-eb21-468e-be50-804db97cf708", "f7a76c21-53d4-46bb-ac53-8b459cae46bd", "c1e3f122-a2ea-442c-854f-20d98f8357a1", "f4769300-b949-4df9-b333-00d33932e9a6"),
        listOf("b802058a-464a-42db-bc10-b650d6f2586a", "97dfb7e7-5161-4015-87a9-c79e6a1952cc", "f5ca7b34-8055-42c0-b836-216129eb7e30", "629cf0de-3ecc-41e7-9926-f7e43eebec51"),
        listOf("4c5e637a-16c7-4de3-9c46-5ed22181962d", "ef0aa05d-8075-4e5d-bead-45be0c3ccbb3", "e180344b-ac83-4483-959e-18a5c56a5e19", "9cab402c-1d37-44b4-886d-fa4f36170a4c"),
        listOf("03219e78-5bc3-44d1-b92e-f63d89cc6526", "4256535c-1ea4-4d4b-8ad5-f9db762eca9e", "c7338b95-52b8-4542-aa79-42eb016c8c1c", "8bb7778b-645b-4475-9a73-1de3170bd3af"),
        listOf("4c9b6dde-6809-46e6-a278-9b6a97588670", "715bdd1a-aa82-436b-b0fa-6acea39bd0a1", "2139e6da-c341-4774-9ac3-b4e026347f64", "d06df0d0-8518-441e-822f-5451d5c595b8"),
        listOf("3eda9b49-2085-498b-9bb2-39a6778493de", "47199894-7cc2-444d-9873-ced2562cc60e", "e48c5a3f-93ef-43bb-a092-2c7ceb946f27", "cecec95a-d894-491a-bee3-5e106fb59f2d"),
        listOf("5a508685-a254-4fba-9b82-9a24b00306af", "db05ea35-0329-4d4b-a53a-6dead03d3852", "cac1105f-619b-4d04-831a-44e1cbf12d57", "6a93130e-1d53-41d1-a9cf-e758800bb179"),
    ).flatMapIndexed { version, guids -> guids.map { it to version } }.toMap()

    // A section header in system.reg, where backslashes are written doubled.
    private val SECTION = Regex(
        """^\[Software\\\\Classes\\\\(?:Wow6432Node\\\\)?CLSID\\\\\{([0-9A-Fa-f-]{36})\}\\\\InprocServer32\]""",
        RegexOption.IGNORE_CASE,
    )
    private val XAPOFX = Regex("""xapofx1_[0-9]\.dll""", RegexOption.IGNORE_CASE)

    /** [systemReg] (a prefix's `system.reg` text) with the XAudio2 classes repaired, and how many were. Pure. */
    fun repair(systemReg: String): Pair<String, Int> {
        var version: Int? = null
        var fixed = 0
        val out = systemReg.split('\n').map { line ->
            if (line.startsWith("[")) {
                version = SECTION.find(line)?.groupValues?.get(1)?.lowercase()?.let(CLASSES::get)
                line
            } else if (version != null && line.startsWith("@=") && XAPOFX.containsMatchIn(line)) {
                fixed++
                XAPOFX.replace(line, "xaudio2_$version.dll")
            } else {
                line
            }
        }
        return out.joinToString("\n") to fixed
    }

    /** Repairs [systemReg] in place when anything needs it; returns how many classes were. Disk work. */
    fun repair(systemReg: File): Int {
        if (!systemReg.isFile) return 0
        val (text, fixed) = repair(systemReg.readText())
        if (fixed == 0) return 0
        val temp = File(systemReg.parentFile, systemReg.name + ".xaudio")
        temp.writeText(text)
        if (!temp.renameTo(systemReg)) {
            temp.delete()
            return 0
        }
        return fixed
    }
}
