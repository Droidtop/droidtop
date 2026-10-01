package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.net.GitHubAuth
import dev.droidtop.net.Http
import java.io.File
import java.security.MessageDigest

/**
 * The download-and-replace half every platform database shares (docs/SPEC.md
 * 7e2). It used to be copy-pasted four times -- one connection block and one
 * temp-then-rename block per database -- which is exactly the kind of
 * duplicated mechanism that drifts: three of the four copies had the same
 * timeouts and the fourth's error text said something different for the same
 * failure. One copy here, four callers.
 *
 * The contract the callers rely on: nothing on disk changes until the
 * downloaded text has PARSED as the database it claims to be, and the
 * replacement itself is a rename, so a killed process leaves either the old
 * file or the new one and never half of either.
 */
object PlatformDatabaseTransport {

    /** Fetches [url], throwing with a readable message on any non-200. */
    fun get(url: String, token: String? = null): String = getOrNull(url, token) ?: error("HTTP 404 from $url")

    /** Fetches [url], or null when the server says the file is not there. */
    fun getOrNull(url: String, token: String? = null): String? {
        return try { Http.get(url, maxBytes = 16L * 1024 * 1024, token = token).text() }
        catch (e: Http.HttpException) { if (e.status == 404 || e.status == 410) null else throw e }
    }

    /** Atomically puts [text] at filesDir/[fileName]; call only after validating it. */
    fun replace(context: Context, fileName: String, text: String) {
        val dest = File(context.filesDir, fileName)
        write(dest, text)
    }

    /** Atomically puts [text] at [dest], creating parent directories. */
    fun write(dest: File, text: String) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".downloading")
        temp.writeText(text)
        check(temp.renameTo(dest) || run { dest.delete(); temp.renameTo(dest) }) {
            "Couldn't move the downloaded ${dest.name} into place"
        }
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
