package dev.droidtop.library.integrations

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IntegrationTest {

    @get:Rule val temp = TemporaryFolder()

    private fun entry(manual: String? = null, video: String? = null) = LibraryEntry(
        id = "/games/psx/Game.chd",
        title = "Game",
        kind = LibraryEntryKind.CONSOLE_ROM,
        manualUri = manual,
        videoUri = video,
    )

    @Test
    fun `parses a complete declaration`() {
        val parsed = Integration.fromJson(
            JSONObject(
                """
                {"id":"reader","label":"Read manual","package":"com.example.reader",
                 "capability":"open_with","argumentsTemplate":"-a android.intent.action.VIEW -d {file.uri} -t application/pdf"}
                """.trimIndent(),
            ),
        )
        assertEquals("reader", parsed?.id)
        assertEquals(IntegrationCapability.OPEN_WITH, parsed?.capability)
        assertEquals("com.example.reader", parsed?.packageName)
    }

    @Test
    fun `an unknown capability is rejected rather than defaulted`() {
        // A capability droidtop does not implement must not silently
        // become one it does -- the trust shapes genuinely differ.
        assertNull(
            Integration.fromJson(
                JSONObject("""{"id":"x","package":"p","capability":"render","argumentsTemplate":"-p p"}"""),
            ),
        )
    }

    @Test
    fun `every declared capability id round-trips`() {
        IntegrationCapability.entries.forEach { capability ->
            assertEquals(capability, IntegrationCapability.fromId(capability.id))
            assertEquals(capability, IntegrationCapability.fromId(capability.id.uppercase()))
        }
    }

    @Test
    fun `label falls back to the id, description to null`() {
        val parsed = Integration.fromJson(
            JSONObject("""{"id":"hook","package":"p","capability":"open_with","argumentsTemplate":"-p p"}"""),
        )
        assertEquals("hook", parsed?.label)
        assertNull(parsed?.description)
    }

    @Test
    fun `placeholder values are handed over raw, never pre-expanded`() {
        // Regression guard for the real launch bug this design avoids: a
        // folder with a space in it must survive as ONE value.
        val values = IntegrationPlaceholders.values(
            systemId = "psx",
            systemFolder = File("/storage/My Games/psx"),
        )
        assertEquals("psx", values[IntegrationPlaceholders.SYSTEM_ID])
        assertEquals("/storage/My Games/psx", values[IntegrationPlaceholders.SYSTEM_FOLDER])
        assertTrue(IntegrationPlaceholders.SYSTEM_NAME !in values)
    }

    @Test
    fun `usedIn reports only the placeholders a template really references`() {
        assertEquals(
            setOf(IntegrationPlaceholders.SYSTEM_ID, IntegrationPlaceholders.QUERY),
            IntegrationPlaceholders.usedIn("-p p --es s {system.id} --es q {query}"),
        )
    }

    @Test
    fun `open-with targets are the files droidtop cannot open, when they exist`() {
        val manual = temp.newFile("Game.pdf")
        val video = temp.newFile("Game.mp4")
        val targets = openWithTargetsFor(entry(manual.absolutePath, video.absolutePath))
        assertEquals(listOf("Manual", "Video"), targets.map { it.label })
        assertEquals(manual.absolutePath, targets[0].file.absolutePath)
    }

    @Test
    fun `a scraped path that no longer exists is dropped, not offered`() {
        val targets = openWithTargetsFor(entry(manual = File(temp.root, "gone.pdf").absolutePath))
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `an entry with no scraped media offers nothing to open`() {
        assertTrue(openWithTargetsFor(entry()).isEmpty())
    }

    @Test
    fun `the game file itself is never an open-with target`() {
        // The player database owns which app launches a ROM; an
        // integration must not become a second way to change that.
        val manual = temp.newFile("Game.pdf")
        val targets = openWithTargetsFor(entry(manual = manual.absolutePath))
        assertTrue(targets.none { it.file.absolutePath == "/games/psx/Game.chd" })
    }

    @Test
    fun `the chip names the file only when there is more than one`() {
        val integration = Integration(
            id = "reader",
            label = "Reader",
            packageName = "com.example.reader",
            capability = IntegrationCapability.OPEN_WITH,
            argumentsTemplate = "-p com.example.reader",
        )
        val target = OpenWithTarget("Manual", File("/m.pdf"))
        assertEquals("Reader", openWithChipLabel(integration, target, 1))
        assertEquals("Reader: Manual", openWithChipLabel(integration, target, 2))
    }
}
