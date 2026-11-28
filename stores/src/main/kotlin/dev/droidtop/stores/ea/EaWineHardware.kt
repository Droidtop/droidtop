package dev.droidtop.stores.ea

import dev.droidtop.library.stores.StoreWineContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

// Probe/format adapted from DroidDeck droiddeck-store-launch at cc63b403872b (GPL-3.0; NOTICE.md).
/** Hardware is read anew in the selected Wine prefix and emulator; no persisted or host-derived substitutes. */
internal object EaWineHardware {
    private val fields = listOf(
        Triple("boardManufacturer", "Win32_BaseBoard", "Manufacturer"), Triple("boardSerial", "Win32_BaseBoard", "SerialNumber"),
        Triple("biosManufacturer", "Win32_BIOS", "Manufacturer"), Triple("biosSerial", "Win32_BIOS", "SerialNumber"),
        Triple("osInstallDate", "Win32_OperatingSystem", "InstallDate"), Triple("osSerial", "Win32_OperatingSystem", "SerialNumber"),
        Triple("gpuPnpId", "Win32_VideoController", "PNPDeviceID"),
    )

    suspend fun probe(wine: StoreWineContext, activationVersion: Int): Map<String, String> {
        val prefix = wine.prefix.canonicalFile
        val tempRoot = File(prefix, "drive_c/windows/temp").canonicalFile
        check(tempRoot.toPath().startsWith(prefix.toPath())) { "The Wine temporary directory leaves its prefix" }
        check(tempRoot.isDirectory || tempRoot.mkdirs()) { "Could not create the EA probe directory" }
        val folder = File(tempRoot, "droidtop-ea-${UUID.randomUUID()}")
        check(folder.mkdir()) { "Could not create the EA probe directory" }
        val outputs = fields.map { File(folder, "${it.first}.txt") } + listOf("volume.txt", "hostname.txt", "cpuid.bin", "cpuid.exe", "probe.bat").map { File(folder, it) }
        try {
            // All paths belong to this private generated directory; cmd metacharacters are never interpolated.
            fun path(file: File): String = wine.windowsPath(file).also { require(it.none { c -> c in "\"%&|<>^!\r\n\u0000" }) { "The EA probe path cannot be passed safely to Wine" } }
            File(folder, "cpuid.exe").writeBytes(EaGuestPrograms.cpuid())
            val script = buildList {
                add("@echo off"); add("setlocal DisableDelayedExpansion")
                fields.forEach { (key, cls, prop) -> add("wmic path $cls get $prop > \"${path(File(folder, "$key.txt"))}\"") }
                add("vol C: > \"${path(File(folder, "volume.txt"))}\"")
                add("hostname > \"${path(File(folder, "hostname.txt"))}\"")
                add("\"${path(File(folder, "cpuid.exe"))}\" > \"${path(File(folder, "cpuid.bin"))}\"")
            }.joinToString("\r\n", postfix = "\r\n")
            val batch = File(folder, "probe.bat").apply { writeText(script, Charsets.US_ASCII) }
            val result = wine.command("cmd.exe", listOf("/d", "/v:off", "/c", path(batch)), folder, 240_000)
            check(result.exitCode == 0) { "Wine could not read this game's activation hardware (exit ${result.exitCode})" }
            fun read(name: String): ByteArray = File(folder, name).also { check(it.isFile && it.length() <= 16_384) { "Wine returned no bounded EA hardware result" } }.readBytes()
            val values = fields.associate { (key, _, _) -> key to wmiValue(decode(read("$key.txt"))) }.toMutableMap()
            val serial = Regex("([0-9A-Fa-f]{4})-([0-9A-Fa-f]{4})").find(decode(read("volume.txt")))
            values["volumeSerial"] = serial?.let { (it.groupValues[1] + it.groupValues[2]).lowercase() }.orEmpty()
            values["hostname"] = decode(read("hostname.txt")).lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }.orEmpty()
            values.putAll(cpuidValues(read("cpuid.bin")))
            check(EaOoa.Hardware.fromWine(values).complete(activationVersion)) {
                "Wine did not report all hardware required for EA activation"
            }
            return values
        } finally {
            outputs.forEach { it.delete() }
            folder.delete()
        }
    }

    internal fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16)
        bytes.size >= 2 && bytes[1] == 0.toByte() -> String(bytes, Charsets.UTF_16LE)
        else -> String(bytes, Charsets.UTF_8)
    }.trimStart('\uFEFF')

    internal fun wmiValue(text: String): String = text.lineSequence().map(String::trim).filter(String::isNotBlank).drop(1).firstOrNull().orEmpty()

    internal fun cpuidValues(raw: ByteArray): Map<String, String> {
        check(raw.size == 80) { "The emulated CPU did not return all EA hardware probe leaves" }
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        fun zeroText(bytes: ByteArray) = String(bytes.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.ISO_8859_1)
        fun word(at: Int) = "%08X".format(java.util.Locale.ROOT, b.getInt(at))
        val vendor = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(b.getInt(4)).putInt(b.getInt(12)).putInt(b.getInt(8)).array()
        check(vendor.any { it != 0.toByte() }) { "The emulated CPU reported no vendor" }
        return mapOf("cpuVendor" to zeroText(vendor), "cpuEax" to word(16), "cpuEdx" to word(28), "cpuEcx" to word(24).lowercase(), "cpuBrand" to zeroText(raw.copyOfRange(32, 80)))
    }
}
