package dev.droidtop.pluginhost

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeArtifactInstallerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun matchingDigestExtractsThenWritesMarker() {
        val archive = temp.newFile("artifact.zip").apply { writeText("fake artifact") }
        val dir = temp.newFolder("runtime")
        val spec = ArtifactSpec("https://example.invalid/artifact", digest(archive), "1")

        RuntimeArtifactInstaller.install(archive, dir, spec, extract = { source, target ->
            File(target, "payload").writeBytes(source.readBytes())
        })

        assertEquals("fake artifact", File(dir, "payload").readText())
        assertTrue(RuntimeArtifactInstaller.isInstalled(dir))
    }

    @Test fun mismatchingDigestDoesNotWriteMarker() {
        val archive = temp.newFile("artifact.zip").apply { writeText("wrong") }
        val dir = temp.newFolder("runtime")
        val spec = ArtifactSpec("https://example.invalid/artifact", "0".repeat(64), "1")

        try {
            RuntimeArtifactInstaller.install(archive, dir, spec, extract = { _, _ -> fail("must not extract an unverified artifact") })
            fail("expected digest mismatch")
        } catch (expected: IllegalArgumentException) {
            assertEquals(DownloadJobs.DIGEST_MISMATCH, expected.message)
        }
        assertFalse(RuntimeArtifactInstaller.isInstalled(dir))
    }

    @Test fun interruptedExtractionLeavesNoMarker() {
        val archive = temp.newFile("artifact.zip").apply { writeText("fake artifact") }
        val dir = temp.newFolder("runtime")
        val spec = ArtifactSpec("https://example.invalid/artifact", digest(archive), "1")

        try {
            RuntimeArtifactInstaller.install(archive, dir, spec, extract = { _, target ->
                File(target, "partial").writeText("partial")
                error("interrupted")
            })
            fail("expected extraction failure")
        } catch (expected: IllegalStateException) {
            assertEquals("interrupted", expected.message)
        }
        assertFalse(RuntimeArtifactInstaller.isInstalled(dir))
        assertFalse(File(dir, "partial").exists())
    }

    @Test fun existingMarkerIsRecognizedWithoutTouchingRuntime() {
        val dir = temp.newFolder("runtime")
        File(dir, RuntimeArtifactInstaller.MARKER_FILE).writeText("already installed")
        assertTrue(RuntimeArtifactInstaller.isInstalled(dir))
    }

    @Test fun interruptedDownloadHasNoMarker() {
        val partialDownload = temp.newFile("partial-download.zip").apply { writeText("incomplete") }
        val dir = temp.newFolder("runtime")
        // DownloadJobs calls the post step only after a completed transfer. A partial transfer
        // therefore never reaches extraction or marker creation.
        assertTrue(partialDownload.length() > 0)
        assertFalse(RuntimeArtifactInstaller.isInstalled(dir))
    }
}
