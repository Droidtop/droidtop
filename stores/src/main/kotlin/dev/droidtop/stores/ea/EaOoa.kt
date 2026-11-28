package dev.droidtop.stores.ea

import org.json.JSONObject
import java.io.File
import org.w3c.dom.Element

/** Origin Online Activation: the per-machine licence an EA game's activation layer checks before it runs. */
// Format algorithms ported from DroidDeck stores/ea/EaOoa.kt at cc63b403872b (GPL-3.0; NOTICE.md).
// Network activation remains a separate owned-game-only launch integration.
internal object EaOoa {

    const val LICENSE_DIR = "ProgramData/Electronic Arts/EA Services/License"
    private const val SIGNATURE_SLOT = 65

    /**
     * What the game sees of the machine inside its Wine prefix: firmware and OS values from Wine's
     * WMI, CPU values from CPUID executed as x86 code, since an emulated game sees the emulator's
     * CPUID rather than the host CPU WMI describes.
     */
    class Hardware(
        val boardManufacturer: String,
        val boardSerial: String,
        val biosManufacturer: String,
        val biosSerial: String,
        val osInstallDate: String,
        val osSerial: String,
        val volumeSerial: String,
        val gpuPnpId: String,
        val cpuVendor: String,
        val cpuEdx: String,
        val cpuEax: String,
        val cpuEcx: String,
        val brand: String,
        val hostname: String,
    ) {
        /** Whether the probe reported everything the hash of this activation version is built from. */
        fun complete(version: Int): Boolean {
            if (version !in 0..4) return false
            val needed = arrayListOf(boardManufacturer, biosManufacturer)
            when {
                version <= 1 -> needed += listOf(hostname, osInstallDate, osSerial)
                version == 2 -> needed += listOf(osInstallDate, osSerial, volumeSerial, cpuVendor, cpuEdx, cpuEcx)
                version == 3 -> needed += listOf(volumeSerial, cpuVendor, cpuEdx, cpuEcx)
                else -> needed += listOf(volumeSerial, cpuVendor, cpuEdx, cpuEax)
            }
            return needed.all { it.isNotEmpty() && it.length <= 1024 } &&
                (version < 2 || (volumeSerial.matches(Regex("[0-9a-f]{8}")) && cpuEdx.matches(Regex("[0-9A-F]{8}")) && cpuEcx.matches(Regex("[0-9a-f]{8}")))) &&
                (version < 4 || cpuEax.matches(Regex("[0-9A-F]{8}")))
        }

        companion object {
            fun fromWine(values: Map<String, String>): Hardware = fromJson(JSONObject(values))

            private fun fromJson(o: JSONObject): Hardware {
                fun s(k: String) = o.optString(k).trim()
                return Hardware(
                    boardManufacturer = s("boardManufacturer"),
                    boardSerial = s("boardSerial").ifEmpty { "None" },
                    biosManufacturer = s("biosManufacturer"),
                    biosSerial = s("biosSerial").ifEmpty { "None" },
                    osInstallDate = s("osInstallDate"),
                    osSerial = s("osSerial"),
                    volumeSerial = s("volumeSerial").replace("-", "").lowercase().let { if (it.isEmpty()) it else it.padStart(8, '0') },
                    gpuPnpId = s("gpuPnpId").ifEmpty { "None" },
                    cpuVendor = s("cpuVendor"),
                    cpuEdx = s("cpuEdx").uppercase(),
                    cpuEax = s("cpuEax").uppercase(),
                    cpuEcx = s("cpuEcx").lowercase(),
                    brand = o.optString("cpuBrand"),
                    hostname = s("hostname"),
                )
            }
        }
    }

    fun machineHash(hw: Hardware, version: Int): String {
        require(hw.complete(version)) { "Wine did not report the hardware required for this EA activation version" }
        val edxEax = hw.cpuEdx + hw.cpuEax
        val edx = hw.cpuEdx.lowercase()
        val shortGpu = hw.gpuPnpId.substringBeforeLast('\\', hw.gpuPnpId)
        val parts = arrayListOf(hw.boardManufacturer, hw.boardSerial)
        when {
            version <= 1 -> parts += listOf(hw.hostname, hw.biosManufacturer, hw.biosSerial, hw.osInstallDate, hw.osSerial)
            version == 2 -> parts += listOf(hw.biosManufacturer, hw.biosSerial, hw.osInstallDate, hw.osSerial, hw.volumeSerial, shortGpu, hw.cpuVendor, edx, hw.cpuEcx)
            version == 3 -> parts += listOf(hw.biosManufacturer, hw.biosSerial, hw.volumeSerial, shortGpu, hw.cpuVendor, edx, hw.cpuEcx)
            else -> parts += listOf(hw.biosManufacturer, hw.biosSerial, hw.volumeSerial, hw.gpuPnpId, hw.cpuVendor, edxEax)
        }
        val text = StringBuilder(parts.joinToString(";")).append(';')
        if (version >= 2) text.append(hw.brand.take(47).padEnd(47, '\u0000')).append(';')
        val digest = EaCrypto.sha1(text.toString().toByteArray(Charsets.ISO_8859_1))
        return if (version < 4) digest.joinToString("") { Integer.toHexString(it.toInt() and 0xFF) } else EaCrypto.hex(digest)
    }

    /** Version of the game's activation layer, which decides how the machine hash is built. */
    fun activationVersion(core: File): Int? {
        val dll = listOf("activation.dll", "awc.dll").firstNotNullOfOrNull { EaPaths.find(core, it) } ?: return null
        require(dll.isFile && dll.length() <= 32L * 1024 * 1024) { "The EA activation library is missing or too large to inspect" }
        val (major, minor, patch) = productVersion(dll.readBytes()) ?: return null
        return when {
            major >= 5 -> 4
            major == 4 && (minor > 7 || (minor == 7 && patch >= 6)) -> 3
            major == 4 && minor == 7 -> 2
            major == 4 -> 1
            else -> 0
        }
    }

    /** The activation layer's folder: `Core` beside the game's exe, where EA ships it, or at the game root. */
    fun coreDir(game: File, exe: String): File? {
        val exeDir = exe.replace('\\', '/').substringBeforeLast('/', "")
        val candidates = listOfNotNull(exeDir.takeIf { it.isNotEmpty() }?.let { "$it/Core" }, "Core")
        return candidates.firstNotNullOfOrNull { EaPaths.find(game, it)?.takeIf { f -> f.isDirectory } }
    }

    /** Games built on the 2012-era EA Core client, which want an opaque token rather than a JWS one. */
    fun legacyCore(core: File?): Boolean = core != null && EaPaths.find(core, "EACore.dll") != null

    private fun encodedSignature(core: File): Boolean = EaPaths.find(core, "activation.exe") != null

    internal fun productVersion(pe: ByteArray): Triple<Int, Int, Int>? {
        val magic = byteArrayOf(0xBD.toByte(), 0x04, 0xEF.toByte(), 0xFE.toByte())
        var i = 0
        while (i + 52 <= pe.size) {
            if (pe[i] == magic[0] && pe[i + 1] == magic[1] && pe[i + 2] == magic[2] && pe[i + 3] == magic[3] &&
                pe[i + 4] == 0.toByte() && pe[i + 5] == 0.toByte() && pe[i + 6] == 1.toByte() && pe[i + 7] == 0.toByte()) {
                fun u32(at: Int) = (pe[at].toLong() and 0xFF) or ((pe[at + 1].toLong() and 0xFF) shl 8) or ((pe[at + 2].toLong() and 0xFF) shl 16) or ((pe[at + 3].toLong() and 0xFF) shl 24)
                val ms = u32(i + 16)
                val ls = u32(i + 20)
                return Triple((ms ushr 16).toInt(), (ms and 0xFFFF).toInt(), (ls ushr 16).toInt())
            }
            i++
        }
        return null
    }

    class License(val fields: LinkedHashMap<String, String>, val namespace: String, val signature: String) {
        val gameToken: String? get() = fields["GameToken"]?.takeIf { it.isNotEmpty() }
        val contentId: String get() = fields["ContentId"].orEmpty()
    }

    internal fun parseLicense(xml: String, signature: String): License {
        require(signature.length <= 2048 && signature.none { it.isISOControl() }) { "EA returned an invalid licence signature" }
        val root = EaXml.root(xml.trim(), 256 * 1024)
        require(root.tagName == "License") { "EA returned no licence document" }
        val fields = LinkedHashMap<String, String>()
        val ns = root.getAttribute("xmlns")
        require(ns.length <= 2048) { "EA returned an invalid licence namespace" }
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val child = nodes.item(i) as? Element ?: continue
            require(child.tagName.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")) && child.getElementsByTagName("*").length == 0 &&
                !fields.containsKey(child.tagName) && fields.size < 64 && child.textContent.length <= 16384) { "EA returned unsupported licence fields" }
            fields[child.tagName] = child.textContent
        }
        return License(fields, ns, signature)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun licenseXml(l: License): String {
        val order = listOf("CipherKey", "MachineHash", "ContentId", "UserId", "GameToken", "GrantTime", "StartTime", "Nonce")
        val body = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        body.append("<License xmlns=\"").append(esc(l.namespace)).append("\">")
        for (k in order) {
            val v = l.fields[k] ?: continue
            if (k == "GameToken" && v.isEmpty()) continue
            body.append('<').append(k).append('>').append(esc(v)).append("</").append(k).append('>')
        }
        return body.append("</License>").toString()
    }

    fun licenseBlob(l: License, encoded: Boolean): ByteArray {
        val signature = if (encoded) l.signature.toByteArray() else java.util.Base64.getDecoder().decode(l.signature)
        val head = ByteArray(SIGNATURE_SLOT)
        System.arraycopy(signature, 0, head, 0, signature.size.coerceAtMost(SIGNATURE_SLOT))
        return head + EaCrypto.licenseEncrypt(licenseXml(l).toByteArray())
    }

    /** Relative filenames only; no network request or activation occurs in this format layer. */
    fun files(core: File, license: License): List<Pair<String, ByteArray>> {
        val id = license.contentId
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "EA returned an invalid licence content identifier" }
        val blob = licenseBlob(license, encodedSignature(core))
        return listOf("$LICENSE_DIR/$id.dlf" to blob, "$LICENSE_DIR/${id}_cached.dlf" to blob)
    }

}
