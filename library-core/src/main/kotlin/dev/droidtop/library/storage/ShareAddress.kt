package dev.droidtop.library.storage

/**
 * What kind of share an address names, and its parts, so one Add share asks for the address first and fills in the
 * rest (docs/SPEC.md "Settings > Network", mytop-project/tracker#964 step 7). Pure.
 *
 * - `smb://[user@]server/share[/folder]` or `\\server\share[\folder]`: SMB.
 * - `sftp://[user@]host[:port][/folder]` (or `ssh://`): SFTP.
 * - `s3://bucket[/folder]`, or an https address on an S3 endpoint (`*.amazonaws.com`, `s3.*`): S3.
 * - any other `http://` or `https://` address: WebDAV.
 */
object ShareAddress {
    data class Parsed(
        val kind: String,
        val host: String = "",
        val port: String = "",
        val user: String = "",
        val share: String = "",
        val folder: String = "",
        /** WebDAV's whole address, or S3's endpoint. */
        val url: String = "",
        val bucket: String = "",
    )

    /** The kind [address] names, or null when it names none droidtop knows (the person then picks one). */
    fun kindOf(address: String): String? = parse(address)?.kind

    fun parse(address: String): Parsed? {
        val text = address.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("\\\\")) return smb(text.removePrefix("\\\\").replace('\\', '/'))
        val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
        val rest = text.substringAfter("://", missingDelimiterValue = "")
        return when (scheme) {
            "smb" -> smb(rest)
            "sftp", "ssh" -> sftp(rest)
            "s3" -> Parsed(StorageShares.S3, bucket = rest.substringBefore('/'), folder = folderOf(rest))
            "http", "https" -> if (isS3Endpoint(rest.substringBefore('/'))) {
                Parsed(StorageShares.S3, url = "$scheme://${rest.substringBefore('/')}", folder = folderOf(rest))
            } else {
                Parsed(StorageShares.WEBDAV, url = text)
            }
            else -> null
        }
    }

    private fun smb(rest: String): Parsed {
        val (user, place) = userAndPlace(rest)
        val parts = place.split('/').filter { it.isNotEmpty() }
        return Parsed(
            StorageShares.SMB,
            host = parts.getOrElse(0) { "" },
            user = user,
            share = parts.getOrElse(1) { "" },
            folder = parts.drop(2).joinToString("/"),
        )
    }

    private fun sftp(rest: String): Parsed {
        val (user, place) = userAndPlace(rest)
        val hostPort = place.substringBefore('/')
        val port = hostPort.substringAfterLast(':', missingDelimiterValue = "").takeIf { p -> p.isNotEmpty() && p.all { it.isDigit() } }
        return Parsed(
            StorageShares.SFTP,
            host = if (port != null) hostPort.substringBeforeLast(':') else hostPort,
            port = port.orEmpty(),
            user = user,
            folder = folderOf(place),
        )
    }

    private fun userAndPlace(rest: String): Pair<String, String> {
        val authority = rest.substringBefore('/')
        if ('@' !in authority) return "" to rest
        return authority.substringBeforeLast('@') to rest.substringAfter('@')
    }

    private fun folderOf(rest: String): String = rest.substringAfter('/', missingDelimiterValue = "").trim('/')

    private fun isS3Endpoint(host: String): Boolean {
        val h = host.lowercase().substringBefore(':')
        return h.endsWith(".amazonaws.com") || h.startsWith("s3.") || h.contains(".s3.")
    }
}
