package dev.droidtop.pluginhost.hosts

/**
 * One file a file host serves directly: its name, its size and SHA-256 (or MD5, for a host that gives only that) when
 * the host says, the address to download it from, and the [headers] that address needs (the person's own account, as
 * a header that goes to that host only). A host that serves the file encrypted gives the key to decrypt it with as
 * [decrypt] ([HostDecryption]); it goes to no server.
 */
data class HostFile(
    val name: String,
    val size: Long?,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val sha256: String? = null,
    val decrypt: String? = null,
    val md5: String? = null,
)

/** What a file host's own API says of a link. */
sealed interface HostResolution {
    data class Files(val files: List<HostFile>) : HostResolution

    /**
     * The host would not serve the link without the person (a rate limit, a captcha, a refused key, a missing file):
     * nothing is solved or worked around, the link is handed on to the next way ([HostLinks]). One sentence.
     */
    data class Refused(val reason: String) : HostResolution
}

/**
 * A file host droidtop downloads from through the host's own API, with the person's account there when they have one
 * (docs/SPEC.md 12a "Host resolvers", Droidtop/tracker#638): one mechanism for every host, PixelDrain the first. Blocking
 * network calls: off the main thread.
 */
interface HostResolver {
    /** The id the person's account on the host is stored under ("pixeldrain"). */
    val id: String

    /** The host's own name, as the job line says it ("direct from PixelDrain"). */
    val name: String

    /** Whether [url] is a link this host's API can serve. Pure. */
    fun matches(url: String): Boolean

    /**
     * [url]'s files, with [account] (the person's API key on the host) when given and anonymously when not. A network
     * failure is an `IOException`, a link the host will not serve [HostResolution.Refused].
     */
    fun resolve(url: String, account: String?): HostResolution

    /** Whether [account] works: null when it does, else one sentence. */
    fun check(account: String): String?
}
