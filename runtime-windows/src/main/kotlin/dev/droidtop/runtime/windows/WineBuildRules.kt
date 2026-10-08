package dev.droidtop.runtime.windows

/**
 * What makes a Wine or Proton build usable on this runtime, as plain values
 * so every rule is checked by a unit test (docs/SPEC.md 5a, "Any Wine
 * build"). The same rules decide the catalog's ids in droidtop-components
 * (tools/build_catalog.py, checked against the same cases): both sides must
 * name a build alike or it would never count as installed.
 *
 * - **Name.** The runtime finds a build by an id of the form
 *   `<wine|proton>-<version>[-<revision>][-<flavour>]-<x86|x86_64|arm64ec>-<code>`
 *   (WineInfo's pattern; droidtop added the flavour word so builds like
 *   GE-Proton or a "custom" build keep apart). A build whose profile names
 *   it otherwise is installed under [canonicalName], and its one-digit code
 *   under [runtimeVerCode].
 * - **Kind.** Its unix side must be an Android (bionic) program: a build
 *   linked against glibc is a Linux build, which needs droidtop's Linux
 *   engine, not this runtime.
 * - **Architecture**, read from the build's own wine binaries, never from its
 *   name: an aarch64 build is an ARM (arm64ec) build; an x86_64 build runs
 *   under Box64 on ARM and directly on an x86_64 device, there only from
 *   Wine 10 on ([WineOptionPlan.runsOnX86Host]); a 32-bit-only build runs
 *   nowhere here.
 */
object WineBuildRules {
    const val BIONIC = "bionic"
    const val LINUX_GLIBC = "linux-glibc"

    data class Elf(val machine: String, val interp: String?)

    private val TYPES = listOf("wine", "proton")
    private val ARCH_TOKENS = mapOf(
        "x86_64" to "x86_64", "amd64" to "x86_64", "x64" to "x86_64", "arm64ec" to "arm64ec",
        "aarch64" to "arm64ec", "arm64" to "arm64ec", "x86" to "x86", "i386" to "x86",
    )
    private val RUNTIME_NAME =
        Regex("""^(wine|proton|Proton)-([0-9.]+)(?:-([0-9.]+))?(?:-[a-z][a-z0-9.]*)?-(x86|x86_64|arm64ec)$""")
    private val NUMBER = Regex("""^\d+(\.\d+)*$""")

    /** What the runtime's profile reader makes of a profile's versionName: the type word first. */
    fun runtimeVerName(type: String, verName: String): String {
        val t = type.lowercase()
        return if (verName.lowercase().startsWith(t)) verName else "$t-$verName"
    }

    /**
     * The name a build is installed under: [verName] itself when the runtime
     * reads it and it names [arch]; otherwise rebuilt from its words. Null
     * when it names no type or no version.
     */
    fun canonicalName(verName: String, arch: String?): String? {
        if (RUNTIME_NAME.matches(verName) && (arch == null || verName.endsWith("-$arch"))) return verName
        val toks = verName.lowercase().split(Regex("""[-\s]+""")).filter { it.isNotEmpty() }
        // "wine-proton-11.0-1" (a Wine-typed profile of a Proton build) is a Proton.
        val type = when {
            "proton" in toks -> "proton"
            "wine" in toks -> "wine"
            else -> return null
        }
        var rest = toks.filter { it !in TYPES }
        val namedArch = rest.firstNotNullOfOrNull { ARCH_TOKENS[it] }
        rest = rest.filter { it !in ARCH_TOKENS && it != "wow64" }
        val finalArch = arch ?: namedArch ?: return null
        val vi = rest.indexOfFirst { NUMBER.matches(it) }
        if (vi < 0) return null
        val version = rest[vi]
        val rev = rest.getOrNull(vi + 1)?.takeIf { NUMBER.matches(it) }
        val used = if (rev != null) setOf(vi, vi + 1) else setOf(vi)
        var flavour = rest.filterIndexed { i, _ -> i !in used }
            .map { it.replace(Regex("[^a-z0-9.]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(".")
        if (flavour.isNotEmpty() && !flavour[0].isLetter()) flavour = "r$flavour"
        return (listOf(type, version) + listOfNotNull(rev) + listOfNotNull(flavour.ifEmpty { null }) + finalArch).joinToString("-")
    }

    /** WineInfo drops an id's last two characters ("-1"), so the version code is one digit. */
    fun runtimeVerCode(code: Int): Int = if (code in 0..9) code else 0

    /** Machine and interpreter of an ELF file from its first bytes (64 KB is plenty), or null. */
    fun elf(head: ByteArray): Elf? {
        if (head.size < 64 || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) return null
        val is64 = head[4].toInt() == 2
        val little = head[5].toInt() == 1
        fun u16(o: Int) = if (little) (head[o].toInt() and 0xff) or ((head[o + 1].toInt() and 0xff) shl 8)
        else ((head[o].toInt() and 0xff) shl 8) or (head[o + 1].toInt() and 0xff)
        fun u32(o: Int): Long = if (little) (u16(o).toLong() or (u16(o + 2).toLong() shl 16))
        else ((u16(o).toLong() shl 16) or u16(o + 2).toLong())
        fun u64(o: Int): Long = if (little) (u32(o) or (u32(o + 4) shl 32)) else ((u32(o) shl 32) or u32(o + 4))
        val machine = u16(18)
        val phoff = if (is64) u64(32) else u32(28)
        val phentsize = if (is64) u16(54) else u16(42)
        val phnum = if (is64) u16(56) else u16(44)
        var interp: String? = null
        for (i in 0 until phnum) {
            val off = phoff + i.toLong() * phentsize
            if (off < 0 || off + phentsize > head.size) break
            val o = off.toInt()
            if (u32(o) != 3L) continue // PT_INTERP
            val pOff = if (is64) u64(o + 8) else u32(o + 4)
            val pSz = if (is64) u64(o + 32) else u32(o + 16)
            if (pOff >= 0 && pSz > 0 && pOff + pSz <= head.size) {
                interp = String(head, pOff.toInt(), pSz.toInt(), Charsets.UTF_8).trimEnd('\u0000')
            }
        }
        val name = when (machine) {
            0xB7 -> "aarch64"
            0x3E -> "x86_64"
            0x03 -> "x86"
            else -> "0x" + machine.toString(16)
        }
        return Elf(name, interp)
    }

    /** bionic: Android's linker, this runtime; linux-glibc: a glibc userland. */
    fun engine(interp: String?): String =
        if (interp == null || interp.contains("linker")) BIONIC else LINUX_GLIBC

    /** The build architecture an ELF machine stands for, or null for one this runtime has no Wine for. */
    fun buildArch(machine: String): String? = when (machine) {
        "aarch64" -> "arm64ec"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> null
    }

    /**
     * Why the build named [name] whose wine binaries are [elf] cannot run on
     * this device ([x86Host]: an x86_64 one), or null when it can.
     */
    fun refusal(x86Host: Boolean, elf: Elf?, name: String): String? {
        if (elf == null) return "Its wine programs could not be read, so it is not a Wine build this runtime can use"
        if (engine(elf.interp) != BIONIC) {
            return "This is a Linux build (it needs ${elf.interp}). Linux builds need droidtop's Linux engine, " +
                "which is not available yet; builds packed for Winlator or GameNative (.wcp) run here"
        }
        return when (buildArch(elf.machine)) {
            null -> "It is built for ${elf.machine}, which this runtime has no Wine for"
            "x86" -> "It is a 32-bit-only build; this runtime runs x86_64 and ARM (arm64ec) builds"
            "arm64ec" -> if (x86Host) "It is an ARM (arm64ec) build; this device runs x86_64 builds" else null
            else -> if (x86Host && !WineOptionPlan.runsOnX86Host(name)) {
                "x86_64 builds older than Wine 10 run only under Box64 on ARM; on this device choose Wine 10 or later"
            } else {
                null
            }
        }
    }
}
