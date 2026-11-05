package dev.droidtop.runtime.windows.redist

/**
 * The redistributables droidtop recognises (docs/SPEC.md 9, "Redistributable installers"; Droidtop/tracker#916), each
 * matched by the installer's version info AND its signer, never by its file name.
 *
 * [Row.provided]: droidtop already provides that runtime (Wine Mono covers .NET Framework and XNA, #895; Wine's own
 * Direct3D/XAudio/XInput and DXVK/VKD3D cover DirectX), so the installer is not run and nothing is asked. Otherwise the
 * installer runs once per prefix with [Row.switches], after the person accepted [Row.licence] (or had accepted it before
 * and asked droidtop to remember).
 */
object RedistTable {
    data class Licence(
        /** Who grants it, as the sheet says it. */
        val vendor: String,
        /** What it covers, as the sheet says it. */
        val product: String,
        /** Changes when the vendor's terms change; part of a remembered acceptance's key. */
        val version: String,
        /** Where the vendor publishes the terms. */
        val url: String,
    )

    data class Row(
        /** Stable id, also the prefix record's key with the architecture: "vcredist-2015-2022". */
        val id: String,
        /** What the person reads: "Microsoft Visual C++ 2015-2022 Redistributable". */
        val title: String,
        val signers: List<String>,
        val company: Regex,
        val product: Regex,
        val switches: List<String>,
        val licence: Licence?,
        val provided: Boolean = false,
    )

    private val MICROSOFT = listOf("Microsoft Corporation")
    private fun msvc(year: String) = Licence("Microsoft", "Microsoft Visual C++ $year Redistributable", year,
        "https://visualstudio.microsoft.com/license-terms/")

    val ROWS = listOf(
        Row("vcredist-2015-2022", "Microsoft Visual C++ 2015-2022 Redistributable", MICROSOFT, Regex("Microsoft", RegexOption.IGNORE_CASE),
            Regex("""Visual C\+\+ (2015|2017|2019|2022)(-20\d\d)? Redistributable""", RegexOption.IGNORE_CASE),
            listOf("/install", "/quiet", "/norestart"), msvc("2015-2022")),
        Row("vcredist-2013", "Microsoft Visual C++ 2013 Redistributable", MICROSOFT, Regex("Microsoft", RegexOption.IGNORE_CASE),
            Regex("""Visual C\+\+ 2013 Redistributable""", RegexOption.IGNORE_CASE), listOf("/install", "/quiet", "/norestart"), msvc("2013")),
        Row("vcredist-2012", "Microsoft Visual C++ 2012 Redistributable", MICROSOFT, Regex("Microsoft", RegexOption.IGNORE_CASE),
            Regex("""Visual C\+\+ 2012 Redistributable""", RegexOption.IGNORE_CASE), listOf("/install", "/quiet", "/norestart"), msvc("2012")),
        Row("vcredist-2010", "Microsoft Visual C++ 2010 Redistributable", MICROSOFT, Regex("Microsoft", RegexOption.IGNORE_CASE),
            Regex("""Visual C\+\+ 2010\b.*Redistributable""", RegexOption.IGNORE_CASE), listOf("/q", "/norestart"), msvc("2010")),
        Row("vcredist-2008", "Microsoft Visual C++ 2008 Redistributable", MICROSOFT, Regex("Microsoft", RegexOption.IGNORE_CASE),
            Regex("""Visual C\+\+ 2008 Redistributable""", RegexOption.IGNORE_CASE), listOf("/q"), msvc("2008")),
        Row("vcredist-2005", "Microsoft Visual C++ 2005 Redistributable", MICROSOFT, Regex("Microsoft", RegexOption.IGNORE_CASE),
            Regex("""Visual C\+\+ 2005 Redistributable""", RegexOption.IGNORE_CASE), listOf("/q"), msvc("2005")),
        Row("directx", "Microsoft DirectX End-User Runtime", MICROSOFT, Regex("Microsoft", RegexOption.IGNORE_CASE),
            Regex("""DirectX""", RegexOption.IGNORE_CASE), listOf("/silent"), null, provided = true),
        Row("dotnet", "Microsoft .NET Framework", MICROSOFT, Regex("Microsoft", RegexOption.IGNORE_CASE),
            Regex("""\.NET Framework""", RegexOption.IGNORE_CASE), listOf("/q", "/norestart"), null, provided = true),
        Row("xna", "Microsoft XNA Framework Redistributable", MICROSOFT, Regex("Microsoft", RegexOption.IGNORE_CASE),
            Regex("""XNA""", RegexOption.IGNORE_CASE), listOf("/q"), null, provided = true),
        Row("physx", "NVIDIA PhysX System Software", listOf("NVIDIA Corporation"), Regex("NVIDIA", RegexOption.IGNORE_CASE),
            Regex("""PhysX""", RegexOption.IGNORE_CASE), listOf("/quiet"),
            Licence("NVIDIA", "NVIDIA PhysX System Software", "1", "https://www.nvidia.com/en-us/drivers/physx/physx-9-21-0713-driver/")),
        Row("openal", "OpenAL", listOf("Creative Technology Ltd", "Creative Labs Inc."), Regex("Creative", RegexOption.IGNORE_CASE),
            Regex("""OpenAL""", RegexOption.IGNORE_CASE), listOf("/s"),
            Licence("Creative Labs", "OpenAL", "1", "https://www.openal.org/")),
    )

    /**
     * The row [info] is, or null: its signer is one of the row's signers and its version info names the row's company and
     * product (ProductName or, failing that, FileDescription). Pure.
     */
    fun match(info: PeInfo): Row? {
        val signer = info.signer ?: return null
        val company = info.company ?: return null
        val names = listOfNotNull(info.product, info.description)
        return ROWS.firstOrNull { row ->
            row.signers.any { it.equals(signer, ignoreCase = true) } &&
                row.company.containsMatchIn(company) &&
                names.any { row.product.containsMatchIn(it) }
        }
    }
}
