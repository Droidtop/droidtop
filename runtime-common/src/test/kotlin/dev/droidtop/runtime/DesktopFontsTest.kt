package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopFontsTest {
    @Test
    fun `the install resumes, installs each distro's Noto packages and marks itself done`() {
        val script = DesktopFonts.installScript()
        val lines = script.lines()
        assertEquals("set -e", lines.first())
        assertTrue(lines.contains("m=${DesktopFonts.MARKER}"))
        assertTrue(script.contains("wget -c -q -O"))
        assertTrue(script.contains("apk add --no-cache ${DesktopFonts.ALPINE_PACKAGES}"))
        assertTrue(script.contains("--download-only ${DesktopFonts.DEBIAN_PACKAGES}"))
        assertTrue(script.contains("apt-get install -y --no-install-recommends ${DesktopFonts.DEBIAN_PACKAGES}"))
        assertEquals("echo 'droidtop: fonts installed'", lines.last { it.isNotBlank() })
        // Shell variables reach the script as themselves, not as Kotlin templates.
        assertTrue(script.contains("if [ -f \"\$m\" ]; then"))
    }
}
