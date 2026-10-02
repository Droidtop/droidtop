package dev.droidtop.shell.gamepad

import dev.droidtop.library.consoles.NoEmulatorInstalled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LaunchFailureMessageTest {
    @Test
    fun noEmulatorInstalledNamesTheSystemNotTheException() {
        val cause = NoEmulatorInstalled(
            systemId = "n64",
            systemName = "Nintendo 64",
            suggestions = listOf("RetroArch"),
            message = "No emulator for Nintendo 64 is installed. droidtop can use RetroArch",
        )
        assertEquals(
            "No Nintendo 64 emulator is installed yet.",
            LaunchFailureMessage.userMessage("Paper Mario", cause),
        )
    }

    @Test
    fun aCauseWithANullMessageStillGetsAPlainSentence() {
        assertEquals(
            "The game couldn't be started.",
            LaunchFailureMessage.userMessage(null, RuntimeException(null as String?)),
        )
        assertEquals(
            "\"Star Fox 64\" couldn't be started.",
            LaunchFailureMessage.userMessage("Star Fox 64", RuntimeException(null as String?)),
        )
    }

    @Test
    fun aLongExceptionMessageNeverReachesTheUser() {
        val longMessage = (
            "java.lang.ClassCastException: dev.droidtop.library.LaunchDisplay\$1 cannot be cast to " +
                "android.content.ComponentName at dev.droidtop.library.LaunchDisplay.startOn (LaunchDisplay.kt:212) "
            ).repeat(4)
        val sentence = LaunchFailureMessage.userMessage("Star Fox 64", RuntimeException(longMessage))
        assertEquals("\"Star Fox 64\" couldn't be started.", sentence)
        assertFalse(sentence.contains("ClassCastException"))
        assertFalse(sentence.contains(longMessage.take(32)))
    }

    @Test
    fun aBlankGameNameFallsBackToTheGenericSentence() {
        assertEquals(
            "The game couldn't be started.",
            LaunchFailureMessage.userMessage("   ", RuntimeException("anything")),
        )
    }
}
