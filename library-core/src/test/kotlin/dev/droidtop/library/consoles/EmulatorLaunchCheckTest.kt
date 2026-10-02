package dev.droidtop.library.consoles

import org.junit.Assert.assertTrue
import org.junit.Test

/** The plain-words launch failures (Droidtop/tracker#248): each names the emulator and says what to do. */
class EmulatorLaunchCheckTest {
    private val name = "Example Emu"

    @Test
    fun aBlockedLaunchSaysTheEmulatorDoesNotAllowIt() {
        val text = explainLaunchFailure(SecurityException("not exported"), name)
        assertTrue(text, "does not let other apps" in text)
    }

    @Test
    fun anInvalidTemplateSaysTheLaunchSettingIsNotValid() {
        val text = explainLaunchFailure(IllegalArgumentException("Bad component name in am start command"), name)
        assertTrue(text, "launch setting for $name is not valid" in text)
        assertTrue(text, "Bad component name" in text)
    }

    @Test
    fun anUnsupportedOptionIsNamed() {
        val text = explainLaunchFailure(AmStartCommandToIntentConverter.UnsupportedArgumentException("--bogus"), name)
        assertTrue(text, "--bogus" in text)
    }

    @Test
    fun anythingElseKeepsItsMessageButNamesTheEmulator() {
        val text = explainLaunchFailure(IllegalStateException("boom"), name)
        assertTrue(text, text.startsWith("$name could not be started"))
        assertTrue(text, "boom" in text)
    }
}
