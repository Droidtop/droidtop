package dev.droidtop.stores.ea

import dev.droidtop.shared.net.BoundedStreamCopy
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket

/** Kernel socket ownership only. Missing Android access is refusal, never permission to mint credentials. */
internal object EaPeerIdentity {
    private const val MAX_TABLE_BYTES = 1024L * 1024
    private val SPACE = Regex("\\s+")

    fun isThisApp(client: Socket): Boolean = runCatching {
        val expectedUid = android.os.Process.myUid()
        if (!client.isConnected || client.isClosed || expectedUid < 0 ||
            !client.inetAddress.isLoopbackAddress || !client.localAddress.isLoopbackAddress) return false
        val tables = listOf("/proc/net/tcp", "/proc/net/tcp6").map { path ->
            val output = ByteArrayOutputStream()
            File(path).inputStream().use { BoundedStreamCopy.copy(it, output, MAX_TABLE_BYTES) }
            output.toByteArray().also { bytes -> check(bytes.all { it >= 0 }) }.toString(Charsets.US_ASCII)
        }
        owner(tables, client.inetAddress, client.port, client.localAddress, client.localPort) == expectedUid
    }.getOrDefault(false)

    /** Both tables must be readable and complete; all four endpoints and ESTABLISHED state must match uniquely. */
    internal fun owner(tables: List<String>, clientAddress: InetAddress, clientPort: Int,
                       serverAddress: InetAddress, serverPort: Int): Int? {
        if (tables.size != 2 || clientPort !in 1..65535 || serverPort !in 1..65535) return null
        val owners = ArrayList<Int>()
        for (table in tables) {
            if (table.length > MAX_TABLE_BYTES || !table.endsWith('\n') || table.any { it.code > 127 }) return null
            val lines = table.lineSequence().filter { it.isNotBlank() }.iterator()
            if (!lines.hasNext()) return null
            val header = lines.next().trim().split(SPACE)
            if (header.getOrNull(1) != "local_address" || header.getOrNull(2) !in setOf("rem_address", "remote_address")) return null
            for (line in lines) {
                val fields = line.trim().split(SPACE)
                if (fields.size < 10) return null
                val local = endpoint(fields[1]) ?: return null
                val remote = endpoint(fields[2]) ?: return null
                if (fields[3].equals("01", true) && local.second == clientPort && remote.second == serverPort &&
                    local.first.contentEquals(normalize(clientAddress.address)) && remote.first.contentEquals(normalize(serverAddress.address))) {
                    if (fields[7].any { !it.isDigit() } || fields[9].toLongOrNull()?.takeIf { it > 0 } == null) return null
                    val uid = fields[7].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                    owners += uid
                    if (owners.size > 1) return null
                }
            }
        }
        return owners.singleOrNull()
    }

    private fun endpoint(value: String): Pair<ByteArray, Int>? {
        val pieces = value.split(':')
        if (pieces.size != 2 || pieces[1].length != 4) return null
        val port = pieces[1].toIntOrNull(16)?.takeIf { it in 0..65535 } ?: return null
        val hex = pieces[0]
        if (hex.length !in setOf(8, 32) || hex.any { it !in "0123456789abcdefABCDEF" }) return null
        val bytes = ByteArray(hex.length / 2)
        // Supported Android ABIs (arm64-v8a/x86_64) expose each proc address word in little-endian order.
        for (word in hex.indices step 8) for (byte in 0..3) {
            val start = word + byte * 2
            bytes[word / 2 + 3 - byte] = (hex.substring(start, start + 2).toIntOrNull(16) ?: return null).toByte()
        }
        return normalize(bytes) to port
    }

    private fun normalize(bytes: ByteArray): ByteArray =
        if (bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } && bytes[10] == (-1).toByte() && bytes[11] == (-1).toByte()) bytes.copyOfRange(12, 16) else bytes
}
