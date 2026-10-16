package dev.droidtop.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing screens have no Surface, which is what gives a `Text` its colour; without one
 * the text is black on the ground's black (Droidtop/tracker#527). Compose draws no pixels in
 * a JVM test, so this holds the line in the source: the screen sets the content colour from
 * the theme before it draws anything.
 */
class PairScreenTextColourTest {
    @Test
    fun `the pairing screen sets its text colour from the theme`() {
        val source = File("src/main/kotlin/dev/droidtop/app/PairComputerActivity.kt").readText()
        val colour = source.indexOf("LocalContentColor provides MaterialTheme.colorScheme.onBackground")
        val screen = source.indexOf("PairScreen(handheld")
        assertTrue("PairComputerActivity sets no content colour", colour >= 0)
        assertTrue("the content colour is set after the screen is drawn", colour < screen)
    }
}
