package dev.droidtop.library

import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class UserFacingErrorMessageTest {

    @Test
    fun `connection errors get the connection message`() {
        assertEquals(
            "Could not connect. Check your connection and try again.",
            userFacingErrorMessage(IOException("timeout")),
        )
        assertEquals(
            "Could not connect. Check your connection and try again.",
            userFacingErrorMessage(UnknownHostException("host")),
        )
        assertEquals(
            "Could not connect. Check your connection and try again.",
            userFacingErrorMessage(TimeoutException()),
        )
    }

    @Test
    fun `other errors get the generic message`() {
        assertEquals(
            "Something went wrong. Try again in a moment.",
            userFacingErrorMessage(RuntimeException("anything")),
        )
        assertEquals(
            "Something went wrong. Try again in a moment.",
            userFacingErrorMessage(null),
        )
    }
}
