package dev.droidtop.pluginhost

import java.io.InputStream
import java.io.OutputStream

/** Copy the already-owned response, with no retry or network request when a body is incomplete. */
internal object BrowserBodyCopy {
    fun receive(input: InputStream, output: OutputStream, declared: Long, limit: Long, beforeRead: () -> Unit = {}, beforeWrite: (Int) -> Unit = {}, progress: (Long) -> Unit = {}): Long {
        require(declared >= -1 && limit > 0 && declared <= limit)
        val bytes = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            beforeRead()
            val count = input.read(bytes)
            if (count < 0) break
            if (count == 0) continue
            check(done <= limit - count) { "The browser download exceeds its size limit" }
            check(declared < 0 || done <= declared - count) { "The browser sent more bytes than it declared" }
            beforeWrite(count)
            output.write(bytes, 0, count)
            done += count
            progress(done)
        }
        check(declared < 0 || done == declared) { "The browser download ended before all bytes arrived" }
        return done
    }
}
