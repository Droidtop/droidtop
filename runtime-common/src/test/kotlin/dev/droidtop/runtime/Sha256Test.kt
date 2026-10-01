package dev.droidtop.runtime

import dev.droidtop.runtime.util.Sha256
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class Sha256Test {
    @Test
    fun `byte array stream and file produce the same lowercase digest`() {
        val bytes = "droidtop".toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, Sha256.hex(bytes))
        assertEquals(expected, Sha256.hex(ByteArrayInputStream(bytes)))
        val file = File.createTempFile("sha256", ".test")
        try {
            file.writeBytes(bytes)
            assertEquals(expected, Sha256.hex(file))
        } finally {
            file.delete()
        }
    }
}
