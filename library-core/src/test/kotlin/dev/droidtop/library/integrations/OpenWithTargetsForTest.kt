package dev.droidtop.library.integrations

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/** What "open with" offers on a game (Droidtop/tracker#418): its manual and its video, only while the files exist. */
class OpenWithTargetsForTest {
    private val dir: File = Files.createTempDirectory("openwith").toFile()

    private fun entry(manual: String? = null, video: String? = null) =
        LibraryEntry(id = "/games/Game", title = "Game", kind = LibraryEntryKind.UNITY, manualUri = manual, videoUri = video)

    @Test
    fun `the manual and the video are offered in that order`() {
        val manual = File(dir, "manual.pdf").apply { writeText("pdf") }
        val video = File(dir, "video.mp4").apply { writeText("mp4") }
        assertEquals(
            listOf(OpenWithTarget("Manual", manual), OpenWithTarget("Video", video)),
            openWithTargetsFor(entry(manual.path, video.path)),
        )
    }

    @Test
    fun `a file that is gone, a folder, or none at all is not offered`() {
        val video = File(dir, "clip.mp4").apply { writeText("mp4") }
        assertEquals(listOf(OpenWithTarget("Video", video)), openWithTargetsFor(entry(File(dir, "gone.pdf").path, video.path)))
        assertEquals(emptyList<OpenWithTarget>(), openWithTargetsFor(entry(dir.path, null)))
        assertEquals(emptyList<OpenWithTarget>(), openWithTargetsFor(entry()))
    }
}
