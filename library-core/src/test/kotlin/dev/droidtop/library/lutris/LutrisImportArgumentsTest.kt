package dev.droidtop.library.lutris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arguments reach Wine as separate argv entries, never through a shell
 * (threat model, decision 4): splitArguments is the only splitter, and
 * arguments refuses anything it cannot split honestly -- a Lutris
 * variable, a control character, an unclosed quote.
 */
class LutrisImportArgumentsTest {

    private val lines = mutableListOf<ImportLine>()

    // ---- splitArguments -----------------------------------------------------

    @Test
    fun `plain arguments split on whitespace`() {
        assertEquals(listOf("-w", "-nolauncher"), LutrisImport.splitArguments("-w -nolauncher"))
    }

    @Test
    fun `double and single quotes each group one argument`() {
        assertEquals(
            listOf("-run", "Program Files/game", "-op", "value with spaces"),
            LutrisImport.splitArguments("-run \"Program Files/game\" -op 'value with spaces'"),
        )
    }

    @Test
    fun `a quoted span can sit inside a token`() {
        assertEquals(listOf("a=b c"), LutrisImport.splitArguments("a='b c'"))
    }

    @Test
    fun `an unclosed quote is refused, never guessed`() {
        assertNull(LutrisImport.splitArguments("-run \"Program Files/game"))
        assertNull(LutrisImport.splitArguments("a 'b c"))
    }

    @Test
    fun `empty input splits into no arguments`() {
        assertEquals(emptyList<String>(), LutrisImport.splitArguments(""))
    }

    // ---- arguments ------------------------------------------------------------

    @Test
    fun `a null or blank args value imports nothing`() {
        assertNull(LutrisImport.arguments(null, lines))
        assertNull(LutrisImport.arguments("   ", lines))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `arguments with a Lutris variable are refused by name`() {
        assertNull(LutrisImport.arguments("\$GAMEDIR --wait", lines))
        assertEquals(
            listOf(ImportLine("Arguments \$GAMEDIR --wait", "They use a Lutris variable droidtop does not fill in")),
            lines,
        )
    }

    @Test
    fun `arguments with a control character are refused, tab included`() {
        assertNull(LutrisImport.arguments("-w\u0007x", lines))
        assertNull(LutrisImport.arguments("a\tb", lines))
        assertEquals(2, lines.size)
        lines.forEach { assertEquals(ImportLine("Arguments", "They contain control characters"), it) }
    }

    @Test
    fun `unclosed quotes in arguments are refused`() {
        assertNull(LutrisImport.arguments("a \"b c", lines))
        assertEquals(listOf(ImportLine("Arguments a \"b c", "A quote is never closed")), lines)
    }
}
