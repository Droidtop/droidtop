package dev.droidtop.pluginhost.hosts

import java.io.File

/**
 * A file a host serves encrypted, such as Mega's (docs/SPEC.md 12a "Host resolvers", Droidtop/tracker#638): the engine
 * fetches the bytes as for any file, and the job decrypts the file where it lies once it is whole, before its digest and
 * its post step, with the key the link gave. The key travels with the download as a header of droidtop's own, which the
 * job takes out before the request goes anywhere ([split]): it is a credential, held in memory by the job as the host
 * account's key is, and never written to the persisted job arguments.
 */
object HostDecryption {
    /** The header that carries the key, as `mega:<key>`; "secret" in its name has the job keep it in memory only. */
    const val SECRET = "X-Droidtop-Decrypt-Secret"

    /** What the job writes to its persisted arguments in the key's place, so a restart knows the file is not to be placed as it is. */
    const val PENDING = "X-Droidtop-Decrypting"

    fun megaSpec(fileKey: ByteArray): String = "mega:${MegaCrypto.encode(fileKey)}"

    /** [headers] as the server is to see them, and the key to decrypt with, null for a file that is not encrypted. */
    fun split(headers: Map<String, String>): Pair<Map<String, String>, String?> {
        val key = headers[SECRET]
        check(key != null || PENDING !in headers) {
            "droidtop keeps the key to decrypt this download in memory only, and it is gone after a restart; start the download again"
        }
        return (headers - SECRET - PENDING) to key
    }

    /**
     * Decrypts [file] in place with the key [spec] holds. Throws a sentence when the key is not usable or the file does
     * not match its checksum: the caller then deletes the file, so a damaged or wrong file is never placed. [alive] lets
     * a cancelled job stop between blocks.
     */
    fun decrypt(spec: String, file: File, alive: () -> Unit) {
        val key = spec.removePrefix("mega:").takeIf { it != spec }
            ?.let { MegaCrypto.decode(it) }?.let { MegaCrypto.fileKey(it) }
            ?: throw IllegalStateException("droidtop cannot read the key to decrypt this download")
        check(MegaCrypto.decryptFile(file, key, alive)) { "The file from Mega does not match its checksum, so it was thrown away" }
    }
}
