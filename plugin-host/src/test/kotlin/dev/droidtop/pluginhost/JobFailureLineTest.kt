package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#790: a plugin job's line is a plain sentence; a runtime's words go behind it as Details. */
class JobFailureLineTest {
    @Test
    fun `the console's class loader message becomes a plain line, and its words the details`() {
        val raw = "dalvik.system.InMemoryDexClassLoader[DexPathList[[dex file \"InMemoryDexFile[cookie=[-5476376639704051696]]\"]," +
            "nativeLibraryDirectories=[/droidtop-sandbox/plugin/lib/arm64-v8a, /system/lib64]]] couldn't find \"libsyncthing.so\""
        val line = JobFailureLine.of("Syncthing", raw)
        assertEquals("Syncthing couldn't load its native code on this device", line.sentence)
        assertEquals(raw, line.detail)
    }

    @Test
    fun `a plugin's own plain sentence stays the line`() {
        val line = JobFailureLine.of("F95zone", "Sign in to f95zone.to first. Then try again")
        assertEquals("Sign in to f95zone.to first. Then try again", line.sentence)
        assertNull(line.detail)
    }

    @Test
    fun `an exception or a stack trace is never the line`() {
        val trace = "java.lang.IllegalStateException: bad state\n\tat dev.example.Plugin.run(Plugin.kt:12)"
        val line = JobFailureLine.of("Example", trace)
        assertEquals("Example ran into a problem it didn't explain", line.sentence)
        assertEquals(trace, line.detail)
        assertTrue(JobFailureLine.of("Example", "x".repeat(JobFailureLine.MAX_PLAIN + 1)).detail != null)
    }

    @Test
    fun `no message at all says the job did not finish`() {
        assertEquals("Example couldn't finish this", JobFailureLine.of("Example", null).sentence)
        assertEquals("Example couldn't finish this", JobFailureLine.of("Example", "  ").sentence)
    }
}
