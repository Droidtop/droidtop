package dev.droidtop.library

import android.content.Context
import java.io.File

/**
 * One kernel-mode service in a Wine prefix's registry (docs/SPEC.md 5b, "Kernel drivers"; Droidtop/tracker#607): a
 * driver an installer registered, by its service [name], its file ([fileName], from the service's ImagePath) and
 * Windows' start type ([start]: 4 is disabled). [product] names the anti-cheat or DRM it belongs to when
 * [KnownDrivers] knows the file.
 */
data class KernelDriver(val name: String, val type: Int, val start: Int?, val imagePath: String?) {
    val fileName: String? get() = imagePath?.substringAfterLast('\\')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val product: String? get() = KnownDrivers.productOf(fileName)

    /** The name approvals and the record use: the driver's file, else its service. Lower case. */
    val key: String get() = (fileName ?: name).lowercase()
}

/**
 * Reads, never writes, the kernel drivers in a Wine prefix (Droidtop/tracker#607, the read-only half). Every kernel
 * or file-system driver service a game's installer left in the prefix is found here, except Wine's own; nothing is
 * changed. Keeping a driver's file out of the prefix and its service disabled is the prefix layer's
 * (docs/SPEC.md 5b, #584 piece 3), not this.
 */
object KernelDrivers {
    const val SERVICE_KERNEL_DRIVER = 1
    const val SERVICE_FILE_SYSTEM_DRIVER = 2

    /** Wine's own kernel services (wine.inf), found by name when their file is not in the prefix to ask. */
    private val WINE_OWN = setOf("mountmgr", "nsiproxy", "winebus", "winehid", "wineusb", "winexinput", "scsiport")

    private val SECTION = Regex("""^\[System\\\\CurrentControlSet\\\\Services\\\\([^\\\]]+)]""", RegexOption.IGNORE_CASE)

    /** Every kernel or file-system driver service in [systemReg], the text of a prefix's `system.reg`. Pure. */
    fun parse(systemReg: String): List<KernelDriver> {
        val found = ArrayList<KernelDriver>()
        var name: String? = null
        var type: Int? = null
        var start: Int? = null
        var image: String? = null
        fun close() {
            val n = name
            if (n != null && (type == SERVICE_KERNEL_DRIVER || type == SERVICE_FILE_SYSTEM_DRIVER)) found += KernelDriver(n, type!!, start, image)
            name = null; type = null; start = null; image = null
        }
        for (raw in systemReg.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                close()
                name = SECTION.find(line)?.groupValues?.get(1)?.let(::unescape)
                continue
            }
            if (name == null) continue
            when {
                line.startsWith("\"Type\"=dword:", ignoreCase = true) -> type = line.substringAfter(':').toIntOrNull(16)
                line.startsWith("\"Start\"=dword:", ignoreCase = true) -> start = line.substringAfter(':').toIntOrNull(16)
                line.startsWith("\"ImagePath\"=", ignoreCase = true) -> image = stringValue(line.substringAfter('='))
            }
        }
        close()
        return found
    }

    /** A `system.reg` string, plain (`"..."`) or expandable (`str(2):"..."`), unescaped; null for another kind. */
    private fun stringValue(raw: String): String? {
        val quoted = raw.removePrefix("str(2):").takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') } ?: return null
        return unescape(quoted.substring(1, quoted.length - 1))
    }

    private fun unescape(text: String): String = text.replace("\\\\", "\\").replace("\\\"", "\"")

    /**
     * The drivers a game's installer left in the Wine prefix at [prefixRoot] (the `.wine` folder): every kernel or
     * file-system service in its `system.reg` that is not Wine's own. Wine's own are told by their file, which Wine
     * marks ("Wine builtin DLL", "Wine placeholder DLL"), or by name when the file is not in the prefix. Disk: IO only.
     */
    fun scan(prefixRoot: File): List<KernelDriver> {
        val reg = File(prefixRoot, "system.reg").takeIf { it.isFile } ?: return emptyList()
        val all = runCatching { parse(reg.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
        return all.filterNot { isWineOwn(it, fileIn(prefixRoot, it.imagePath)) }
    }

    /** Whether [driver] is one of Wine's own, by its [file]'s mark when there is one, else by name. */
    internal fun isWineOwn(driver: KernelDriver, file: File?): Boolean {
        if (file != null && file.isFile) return wineMarked(file)
        return driver.name.lowercase() in WINE_OWN
    }

    /** Wine writes "Wine builtin DLL" or "Wine placeholder DLL" at 0x40 of each PE file it makes. */
    private fun wineMarked(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(0x60)
            val read = input.read(head)
            read > 0x45 && String(head, 0x40, minOf(read, 0x60) - 0x40, Charsets.ISO_8859_1).startsWith("Wine ")
        }
    }.getOrDefault(false)

    /**
     * Where an ImagePath points inside the prefix: `C:\...`, `\SystemRoot\...`, `\??\C:\...` or a path relative to
     * the Windows folder (`System32\drivers\x.sys`). Folder names are matched without case, as Windows does.
     */
    internal fun fileIn(prefixRoot: File, imagePath: String?): File? {
        val path = imagePath?.trim()?.removePrefix("\\??\\")?.takeIf { it.isNotEmpty() } ?: return null
        val parts = when {
            path.length > 2 && path[1] == ':' && path[0].equals('c', ignoreCase = true) -> listOf("drive_c") + path.substring(2).split('\\', '/')
            path.startsWith("\\SystemRoot", ignoreCase = true) -> listOf("drive_c", "windows") + path.substring("\\SystemRoot".length).split('\\', '/')
            path.startsWith("%SystemRoot%", ignoreCase = true) -> listOf("drive_c", "windows") + path.substring("%SystemRoot%".length).split('\\', '/')
            !path.startsWith("\\") && path.getOrNull(1) != ':' -> listOf("drive_c", "windows") + path.split('\\', '/')
            else -> return null
        }.filter { it.isNotEmpty() }
        var at = prefixRoot
        for (part in parts) {
            if (part == "..") return null
            at = at.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return at
    }

    /** One line for a run's record: the driver, its file and product, and whether this game has it approved. Pure. */
    fun recordLine(driver: KernelDriver, approved: Boolean): String {
        val what = listOfNotNull(driver.fileName, driver.product).joinToString(", ")
        val state = if (approved) "approved" else "not approved; droidtop does not yet keep it out of the prefix"
        val disabled = if (driver.start == 4) ", disabled" else ""
        return "${driver.name}${if (what.isEmpty()) "" else " ($what)"}$disabled: $state"
    }
}

/**
 * The anti-cheat and DRM drivers droidtop can name (Droidtop/tracker#607). It only names a driver in the Drivers list,
 * the record and the game's page; whether a driver is kept out never depends on it.
 */
object KnownDrivers {
    private val BY_FILE = mapOf(
        "easyanticheat.sys" to "EasyAntiCheat",
        "easyanticheat_eos.sys" to "EasyAntiCheat",
        "bedaisy.sys" to "BattlEye",
        "vgk.sys" to "Riot Vanguard",
        "mhyprot2.sys" to "miHoYo protect",
        "mhyprot3.sys" to "miHoYo protect",
        "xhunter1.sys" to "XIGNCODE3",
        "faceit.sys" to "FACEIT",
        "ace-base.sys" to "Tencent ACE",
        "secdrv.sys" to "SafeDisc",
        "sfdrv01.sys" to "StarForce",
        "sfhlp02.sys" to "StarForce",
        "sfvfs02.sys" to "StarForce",
    )

    /** The product [fileName] belongs to (any case), or null. Pure. */
    fun productOf(fileName: String?): String? = fileName?.let { BY_FILE[it.lowercase()] }
}

/**
 * Each Windows game's kernel-driver choices (Droidtop/tracker#607, owner 2026-10-23): the drivers it has been seen to
 * install, the ones the person approved, and its default for the rest, which is deny (a placeholder) until the
 * person changes it after a warning. By library id. Disk: IO only.
 */
object KernelDriverPrefs {
    private const val FILE = "droidtop_kernel_drivers"
    private const val ALLOW_UNLISTED = "allow_unlisted:"
    private const val APPROVED = "approved:"
    private const val SEEN = "seen:"
    private const val PRESENT = "present:"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether drivers the person has not approved are let in for [entryId]; false (deny) unless they changed it. */
    fun allowUnlisted(context: Context, entryId: String): Boolean = prefs(context).getBoolean(ALLOW_UNLISTED + entryId, false)

    fun setAllowUnlisted(context: Context, entryId: String, allow: Boolean) {
        prefs(context).edit().putBoolean(ALLOW_UNLISTED + entryId, allow).apply()
    }

    /** The drivers approved for [entryId], by [KernelDriver.key]. */
    fun approved(context: Context, entryId: String): Set<String> = prefs(context).getStringSet(APPROVED + entryId, null).orEmpty()

    fun setApproved(context: Context, entryId: String, key: String, approved: Boolean) {
        val now = approved(context, entryId)
        prefs(context).edit().putStringSet(APPROVED + entryId, if (approved) now + key else now - key).apply()
    }

    /** The drivers [entryId] has been seen to install, as the registry named them. */
    fun seen(context: Context, entryId: String): List<KernelDriver> =
        prefs(context).getStringSet(SEEN + entryId, null).orEmpty().mapNotNull(::decode).sortedBy { it.name.lowercase() }

    /** Adds [drivers] to what [entryId] has been seen to install; answers the ones that are new. */
    fun addSeen(context: Context, entryId: String, drivers: List<KernelDriver>): List<KernelDriver> {
        val known = seen(context, entryId).map { it.key }.toSet()
        val fresh = drivers.filter { it.key !in known }.distinctBy { it.key }
        if (fresh.isNotEmpty()) {
            val stored = prefs(context).getStringSet(SEEN + entryId, null).orEmpty()
            prefs(context).edit().putStringSet(SEEN + entryId, stored + fresh.map(::encode)).apply()
        }
        return fresh
    }

    /** The drivers in [entryId]'s prefix when it last ran, whoever installed them (a shared prefix holds other games'). */
    fun present(context: Context, entryId: String): List<KernelDriver> =
        prefs(context).getStringSet(PRESENT + entryId, null).orEmpty().mapNotNull(::decode).sortedBy { it.name.lowercase() }

    fun setPresent(context: Context, entryId: String, drivers: List<KernelDriver>) {
        prefs(context).edit().putStringSet(PRESENT + entryId, drivers.map(::encode).toSet()).apply()
    }

    internal fun encode(driver: KernelDriver): String =
        listOf(driver.name, driver.type.toString(), driver.imagePath.orEmpty(), driver.start?.toString().orEmpty()).joinToString("\t")

    internal fun decode(text: String): KernelDriver? {
        val parts = text.split('\t')
        val name = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
        val type = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return KernelDriver(name, type, parts.getOrNull(3)?.toIntOrNull(), parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }
}
