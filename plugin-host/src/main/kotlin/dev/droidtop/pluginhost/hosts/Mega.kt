package dev.droidtop.pluginhost.hosts

import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.debrid.DebridTransport
import dev.droidtop.pluginhost.debrid.jsonOrNull
import dev.droidtop.pluginhost.debrid.long
import dev.droidtop.pluginhost.debrid.objects
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mega (https://github.com/meganz/sdk, BSD-2): a file link `mega.nz/file/<handle>#<key>`, a folder link
 * `mega.nz/folder/<handle>#<key>`, or the older `mega.nz/#!<handle>!<key>` and `#F!<handle>!<key>`, on mega.nz, mega.co.nz
 * or mega.io. The key after the `#` is the file's (or the folder's) decryption key: it is never sent to Mega, only used
 * here. A file is one `g` command to Mega's API (`p` its public handle) answering the download address, the size and
 * the encrypted attributes that hold the name; a folder is one `f` command listing its nodes, whose keys the folder
 * key opens, and one `g` command with `n` for each file. The content is AES-CTR encrypted: this gives the address as it
 * is and the key as [HostFile.decrypt], and the download job decrypts the file once it is whole and checks Mega's MAC
 * ([HostDecryption]). No account: Mega's login is not built, so every download is anonymous, and Mega's limit on those
 * (error -17) is a [HostResolution.Refused], nothing worked around. A folder with folders inside it, or with more files
 * than one job takes, is refused: the files of a job land in one folder.
 */
class Mega(
    private val http: DebridTransport = DebridTransport.HTTP,
    firstId: Long = kotlin.random.Random.nextLong(1, 1_000_000_000),
    /** How long to wait before asking again after Mega says -3 (try again later); multiplied by the attempt. */
    private val retryDelayMs: Long = 1_500,
) : HostResolver {
    override val id = "mega"
    override val name = "Mega"

    private val sequence = AtomicLong(firstId)

    private class Link(val folder: Boolean, val handle: String, val key: ByteArray)

    /** What Mega said that stops the link: its sentence, from [resolve] to the caller as a [HostResolution.Refused]. */
    private class Refuse(message: String) : Exception(message)

    private fun parse(url: String): Link? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host !in DOMAINS) return null
        val fragment = uri.fragment ?: return null
        val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val (folder, handle, key) = when {
            parts.isEmpty() -> LEGACY.matchEntire(fragment)?.destructured?.let { (kind, h, k) -> Triple(kind == "F", h, k) } ?: return null
            parts.size == 2 && parts[0] in setOf("file", "folder") -> Triple(parts[0] == "folder", parts[1], fragment)
            else -> return null
        }
        if (!HANDLE.matches(handle) || !KEY.matches(key)) return null
        val bytes = MegaCrypto.decode(key)?.takeIf { it.size == if (folder) 16 else 32 } ?: return null
        return Link(folder, handle, bytes)
    }

    override fun matches(url: String): Boolean = parse(url) != null

    override fun resolve(url: String, account: String?): HostResolution {
        val link = parse(url) ?: return HostResolution.Refused("That is not a Mega file or folder link")
        return try {
            HostResolution.Files(if (link.folder) folder(link) else listOf(fileOf(ask(listOf(download("p", link.handle)), null).opt(0), link.key)))
        } catch (e: Refuse) {
            HostResolution.Refused(e.message.orEmpty())
        }
    }

    private fun folder(link: Link): List<HostFile> {
        val listing = ask(listOf(JSONObject().put("a", "f").put("c", 1).put("r", 1)), link.handle).opt(0)
        failure(listing)?.let { throw it }
        val files = (listing as? JSONObject)?.optJSONArray("f")?.objects().orEmpty().filter { it.optInt("t", -1) == 0 }
        if (files.isEmpty()) throw Refuse("The Mega folder has no files")
        if (files.any { it.optString("p") != link.handle }) throw Refuse("The Mega folder has folders inside it, which droidtop does not download yet")
        if (files.size > DownloadJobs.MAX_FILES) throw Refuse("The Mega folder has more files than one download takes")
        // A node's key is kept AES-ECB encrypted under the folder key: the part of `k` after the owner's handle.
        val keys = files.map { node ->
            node.optString("k").substringBefore('/').substringAfter(':', "").let { MegaCrypto.decode(it) }
                ?.let { MegaCrypto.nodeKey(it, link.key) }?.takeIf { it.size == 32 }
                ?: throw Refuse("The key in that Mega link does not open the folder")
        }
        val replies = ask(files.map { download("n", it.optString("h")) }, link.handle)
        return files.indices.map { fileOf(replies.opt(it), keys[it]) }
    }

    private fun download(handleArgument: String, handle: String) =
        JSONObject().put("a", "g").put("g", 1).put("ssl", 2).put(handleArgument, handle)

    /** A `g` reply as a file: the address, the size, and the name from the attributes the file's key opens. */
    private fun fileOf(reply: Any?, key: ByteArray): HostFile {
        failure(reply)?.let { throw it }
        val json = reply as? JSONObject ?: throw Refuse(UNREADABLE)
        val url = (json.opt("g") as? String)?.takeIf { it.startsWith("https://") } ?: throw Refuse(UNREADABLE)
        val fileKey = MegaCrypto.fileKey(key) ?: throw Refuse(UNREADABLE)
        val name = (json.opt("at") as? String)?.let { MegaCrypto.fileName(it, fileKey.aes) }
            ?: throw Refuse("The key in that Mega link does not open the file")
        return HostFile(name = name, size = json.long("s")?.takeIf { it > 0 }, url = url, decrypt = HostDecryption.megaSpec(key))
    }

    /**
     * Mega's replies to [commands], one each: the commands are POSTed as a JSON array to `/cs`, with `n` the folder for a
     * folder link. Mega says what went wrong as a number below zero, for the whole request or in place of a reply; -3
     * (try again later) is asked again after a pause, up to [TRIES] times.
     */
    private fun ask(commands: List<JSONObject>, folder: String?): JSONArray {
        val url = "$API?id=${sequence.incrementAndGet()}" + (folder?.let { "&n=$it" } ?: "")
        val body = JSONArray(commands).toString()
        for (attempt in 1..TRIES) {
            val (status, text) = http.postJson(url, body)
            if (status != 200) throw Refuse("Mega answered $status")
            val reply = jsonOrNull(text)
            if (reply is Number) {
                if (reply.toInt() == TRY_AGAIN && attempt < TRIES) { pause(attempt); continue }
                throw sentence(reply.toInt())
            }
            val items = (reply as? JSONArray)?.takeIf { it.length() == commands.size } ?: throw Refuse(UNREADABLE)
            if (attempt < TRIES && (0 until items.length()).any { codeOf(items.opt(it)) == TRY_AGAIN }) { pause(attempt); continue }
            return items
        }
        throw sentence(TRY_AGAIN)
    }

    private fun pause(attempt: Int) {
        if (retryDelayMs > 0) Thread.sleep(retryDelayMs * attempt)
    }

    /** The error code a reply is: a bare number, or an object whose `e` says it. Null for a reply that is none. */
    private fun codeOf(reply: Any?): Int? = ((reply as? JSONObject)?.opt("e") ?: reply).let { (it as? Number)?.toInt() }?.takeIf { it < 0 }

    private fun failure(reply: Any?): Refuse? = codeOf(reply)?.let { sentence(it) }

    private fun sentence(code: Int) = Refuse(
        when (code) {
            TRY_AGAIN -> "Mega is busy and asked to try again later"
            -9 -> "Mega does not have that file"
            -11 -> "Mega will not give that link without an account"
            -17 -> "Mega's limit on anonymous downloads is reached"
            else -> "Mega answered error $code"
        },
    )

    /** Mega has no account in droidtop yet (its login is a scheme of its own, a follow-up): nothing to test. */
    override fun check(account: String): String? = "droidtop does not sign in to Mega yet; its downloads are anonymous"

    private companion object {
        const val API = "https://g.api.mega.co.nz/cs"
        const val TRIES = 3
        const val TRY_AGAIN = -3
        const val UNREADABLE = "Mega's reply could not be read"
        val DOMAINS = setOf("mega.nz", "mega.co.nz", "mega.io")
        val HANDLE = Regex("[A-Za-z0-9_-]{8}")
        val KEY = Regex("[A-Za-z0-9_-]{22}|[A-Za-z0-9_-]{43}")
        val LEGACY = Regex("(F?)!([A-Za-z0-9_-]{8})!([A-Za-z0-9_-]+)")
    }
}
