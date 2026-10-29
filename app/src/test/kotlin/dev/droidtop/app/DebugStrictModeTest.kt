package dev.droidtop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugStrictModeTest {
    private fun frame(className: String, method: String = "run") = StackTraceElement(className, method, "File.kt", 1)

    @Test
    fun theFirstFrameOfDroidtopsOwnCodeIsReported() {
        val stack = arrayOf(frame("android.os.StrictMode"), frame("dev.droidtop.library.ScanLog", "install"), frame("dev.droidtop.app.DroidtopApplication"))
        assertEquals("dev.droidtop.library.ScanLog", DebugStrictMode.reportedFrame(stack)!!.className)
    }

    @Test
    fun aLibrarysOwnWorkIsNotReported() {
        assertNull(DebugStrictMode.reportedFrame(arrayOf(frame("android.os.StrictMode"), frame("okhttp3.internal.Util"))))
        assertNull(DebugStrictMode.reportedFrame(emptyArray()))
    }

    @Test
    fun theClassesThatBlockOnPurposeAreNotReported() {
        assertNull(DebugStrictMode.reportedFrame(arrayOf(frame("android.os.StrictMode"), frame("dev.droidtop.app.update.UpdateRelaunch", "arm"))))
        assertNull(DebugStrictMode.reportedFrame(arrayOf(frame("dev.droidtop.library.diagnostics.CrashRecovery\$install\$1"))))
    }
}
