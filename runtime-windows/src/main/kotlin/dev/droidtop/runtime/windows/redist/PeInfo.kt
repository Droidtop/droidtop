package dev.droidtop.runtime.windows.redist

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * What a Windows program says about itself (docs/SPEC.md 9, "Redistributable installers"; Droidtop/tracker#916): its
 * version resource's strings and the organisation its Authenticode signature names. Read straight from the PE file, no
 * Wine involved, so a redistributable is recognised by what it is and never by its file name.
 *
 * The signer is the organisation (`O=`) in the subject of the signature's code-signing certificate. The signature itself
 * is not verified against a trust chain here: this identifies an installer the game ships, it does not vouch for it.
 */
data class PeInfo(
    val is64: Boolean,
    val strings: Map<String, String>,
    val signer: String?,
) {
    val company: String? get() = strings["CompanyName"]
    val product: String? get() = strings["ProductName"]
    val description: String? get() = strings["FileDescription"]
    val fileVersion: String? get() = strings["FileVersion"]
    val originalName: String? get() = strings["OriginalFilename"]

    companion object {
        private const val MAX_RESOURCE = 4 * 1024 * 1024
        private const val MAX_SIGNATURE = 1024 * 1024

        /** [file]'s version strings and signer, or null when it is not a readable PE file. Disk work. */
        fun read(file: File): PeInfo? = runCatching { RandomAccessFile(file, "r").use { parse(it) } }.getOrNull()

        private fun parse(f: RandomAccessFile): PeInfo? {
            fun u16(at: Long): Int { f.seek(at); return f.read() or (f.read() shl 8) }
            fun u32(at: Long): Long { f.seek(at); return (f.read().toLong() or (f.read().toLong() shl 8) or (f.read().toLong() shl 16) or (f.read().toLong() shl 24)) }
            if (u16(0) != 0x5A4D) return null // MZ
            val pe = u32(0x3C)
            if (pe <= 0 || pe > f.length() - 24 || u32(pe) != 0x00004550L) return null
            val coff = pe + 4
            val sections = u16(coff + 2)
            val optSize = u16(coff + 16)
            val opt = coff + 20
            val magic = u16(opt)
            val is64 = magic == 0x20B
            if (!is64 && magic != 0x10B) return null
            val dirs = opt + if (is64) 112 else 96
            val resourceRva = u32(dirs + 2 * 8)
            val resourceSize = u32(dirs + 2 * 8 + 4)
            val securityOffset = u32(dirs + 4 * 8) // a file offset, not an RVA
            val securitySize = u32(dirs + 4 * 8 + 4)
            val sectionTable = opt + optSize
            val table = (0 until sections).map { i ->
                val s = sectionTable + i * 40L
                longArrayOf(u32(s + 12), maxOf(u32(s + 8), u32(s + 16)), u32(s + 20)) // va, size, raw pointer
            }
            fun offsetOf(rva: Long): Long? = table.firstOrNull { rva >= it[0] && rva < it[0] + it[1] }?.let { rva - it[0] + it[2] }

            var strings = emptyMap<String, String>()
            if (resourceRva != 0L && resourceSize in 1..MAX_RESOURCE) {
                val base = offsetOf(resourceRva)
                if (base != null) {
                    val res = ByteArray(minOf(resourceSize, f.length() - base).toInt())
                    f.seek(base)
                    f.readFully(res)
                    strings = versionData(res, resourceRva, ::offsetOf, f)?.let(::versionStrings).orEmpty()
                }
            }
            var signer: String? = null
            if (securityOffset > 0 && securitySize in 9..MAX_SIGNATURE && securityOffset + securitySize <= f.length()) {
                val cert = ByteArray(securitySize.toInt())
                f.seek(securityOffset)
                f.readFully(cert)
                signer = signerOf(cert)
            }
            return PeInfo(is64, strings, signer)
        }

        /** The RT_VERSION resource's bytes: the resource tree's type 16, its first name, its first language. */
        private fun versionData(res: ByteArray, resourceRva: Long, offsetOf: (Long) -> Long?, f: RandomAccessFile): ByteArray? {
            fun u16(at: Int) = (res[at].toInt() and 0xFF) or ((res[at + 1].toInt() and 0xFF) shl 8)
            fun u32(at: Int) = (u16(at).toLong() or (u16(at + 2).toLong() shl 16))
            fun entries(dir: Int): List<Pair<Long, Long>> {
                if (dir < 0 || dir + 16 > res.size) return emptyList()
                val count = u16(dir + 12) + u16(dir + 14)
                return (0 until count).mapNotNull { i ->
                    val e = dir + 16 + i * 8
                    if (e + 8 > res.size) null else u32(e) to u32(e + 4)
                }
            }
            val type = entries(0).firstOrNull { (id, _) -> id == 16L } ?: return null
            if (type.second and 0x80000000L == 0L) return null
            val name = entries((type.second and 0x7FFFFFFF).toInt()).firstOrNull() ?: return null
            if (name.second and 0x80000000L == 0L) return null
            val lang = entries((name.second and 0x7FFFFFFF).toInt()).firstOrNull() ?: return null
            val dataEntry = lang.second.toInt()
            if (dataEntry + 8 > res.size) return null
            val rva = u32(dataEntry)
            val size = u32(dataEntry + 4)
            if (size <= 0 || size > 1024 * 1024) return null
            val at = offsetOf(rva) ?: return null
            if (at + size > f.length()) return null
            return ByteArray(size.toInt()).also { f.seek(at); f.readFully(it) }
        }

        /**
         * The strings of a VS_VERSIONINFO block (its StringFileInfo tables, the first value of each name winning). Pure;
         * internal for tests.
         */
        internal fun versionStrings(block: ByteArray): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            fun u16(at: Int) = if (at + 1 < block.size) (block[at].toInt() and 0xFF) or ((block[at + 1].toInt() and 0xFF) shl 8) else 0
            fun align(at: Int, start: Int) = start + ((at - start + 3) and 3.inv())
            fun utf16z(at: Int, limit: Int): Pair<String, Int> {
                val sb = StringBuilder()
                var p = at
                while (p + 1 < limit) {
                    val c = u16(p)
                    p += 2
                    if (c == 0) break
                    sb.append(c.toChar())
                }
                return sb.toString() to p
            }
            // One node: length, value length, type, key, value, children; depth 0 root, 1 StringFileInfo, 2 table, 3 string.
            fun node(start: Int, depth: Int, inStrings: Boolean) {
                val length = u16(start)
                if (length < 6 || start + length > block.size) return
                val end = start + length
                val valueLength = u16(start + 2)
                val textValue = u16(start + 4) == 1
                val (key, afterKey) = utf16z(start + 6, end)
                val valueStart = align(afterKey, start)
                val valueBytes = if (textValue) valueLength * 2 else valueLength
                if (inStrings && depth == 3) {
                    if (valueLength > 0 && key !in out) out[key] = utf16z(valueStart, minOf(end, valueStart + valueBytes)).first.trim()
                    return
                }
                val strings = inStrings || (depth == 1 && key == "StringFileInfo")
                if (depth == 1 && key != "StringFileInfo") return
                var child = align(valueStart + valueBytes, start)
                while (child + 6 <= end) {
                    val childLength = u16(child)
                    if (childLength < 6) break
                    node(child, depth + 1, strings)
                    child = align(child + childLength, start)
                }
            }
            node(0, 0, false)
            return out
        }

        /**
         * The organisation in the subject of the code-signing certificate in a WIN_CERTIFICATE list's PKCS#7 signature
         * (EKU 1.3.6.1.5.5.7.3.3), or of its first end-entity certificate, or null. Not chain-validated.
         */
        internal fun signerOf(winCertificates: ByteArray): String? {
            fun u32(at: Int) = (0..3).fold(0L) { acc, i -> acc or ((winCertificates[at + i].toLong() and 0xFF) shl (8 * i)) }
            fun u16(at: Int) = ((winCertificates[at].toInt() and 0xFF) or ((winCertificates[at + 1].toInt() and 0xFF) shl 8))
            var at = 0
            while (at + 8 <= winCertificates.size) {
                val length = u32(at).toInt()
                if (length < 8 || at + length > winCertificates.size) return null
                if (u16(at + 6) == 0x0002) { // WIN_CERT_TYPE_PKCS_SIGNED_DATA
                    val der = winCertificates.copyOfRange(at + 8, at + length)
                    val certs = runCatching {
                        CertificateFactory.getInstance("X.509").generateCertPath(ByteArrayInputStream(der), "PKCS7")
                            .certificates.filterIsInstance<X509Certificate>()
                    }.getOrNull().orEmpty()
                    val leaf = certs.firstOrNull { c -> runCatching { c.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.3") == true }.getOrDefault(false) }
                        ?: certs.firstOrNull { it.basicConstraints < 0 }
                    leaf?.let { return organisation(it.subjectX500Principal.name) }
                }
                at += (length + 7) and 7.inv()
            }
            return null
        }

        /** The O= of an RFC 2253 name, else its CN=, unescaped. Pure. */
        internal fun organisation(rfc2253: String): String? {
            val parts = Regex("""(?:^|,)\s*([A-Za-z0-9.]+)=((?:\\.|[^,\\])*)""").findAll(rfc2253)
                .associate { it.groupValues[1].uppercase() to it.groupValues[2].replace(Regex("""\\(.)"""), "$1").trim('"') }
            return parts["O"] ?: parts["CN"]
        }
    }
}
