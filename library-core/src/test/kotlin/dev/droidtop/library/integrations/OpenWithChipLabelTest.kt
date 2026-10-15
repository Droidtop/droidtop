package dev.droidtop.library.integrations

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The chip names the file only when the entry has more than one openable file. */
class OpenWithChipLabelTest {

    private val integration = Integration(
        id = "pdf",
        label = "Open in my PDF reader",
        packageName = "com.example.reader",
        capability = IntegrationCapability.OPEN_WITH,
        argumentsTemplate = "-a android.intent.action.VIEW",
    )
    private val target = OpenWithTarget("Manual", File("/tmp/manual.pdf"))

    @Test
    fun `a single target leaves the chip as the integration label`() {
        assertEquals("Open in my PDF reader", openWithChipLabel(integration, target, 1))
    }

    @Test
    fun `two targets add the file label so the chips differ`() {
        assertEquals("Open in my PDF reader: Manual", openWithChipLabel(integration, target, 2))
    }

    @Test
    fun `no targets also leaves the plain label`() {
        assertEquals("Open in my PDF reader", openWithChipLabel(integration, target, 0))
    }
}
