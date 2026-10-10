package dev.droidtop.runtime.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Apps tab's figures (AppsInsights.kt, Droidtop/tracker#414 slice C20). */
class AppsInsightsTest {
    private fun app(pkg: String) = RunningApp(pkg, pkg, 0, null, true)
    private val apps = listOf(app("a.recent"), app("b.heavy"), app("c.light"))

    @Test fun `sort order, recent keeps the list, a figure sorts highest first and unknowns last`() {
        assertEquals(apps, AppsInsights.sort(apps, AppsInsights.Sort.RECENT, mapOf("b.heavy" to 9.0)))
        assertEquals(
            listOf("b.heavy", "c.light", "a.recent"),
            AppsInsights.sort(apps, AppsInsights.Sort.CPU, mapOf("b.heavy" to 30.0, "c.light" to 2.0)).map { it.packageName },
        )
    }

    @Test fun `meminfo compact lines add up per package, services included`() {
        val text = """
            time,123,456
            proc,native,surfaceflinger,600,40000,N/A,e
            proc,cached,com.example.game,4321,180000,N/A,e
            proc,cached,com.example.game:remote,4400,20000,N/A,e
            oom,cached,200000
        """.trimIndent()
        val memory = AppsInsights.parseMeminfo(text)
        assertEquals(200000.0, memory.getValue("com.example.game"), 0.01)
        assertEquals(40000.0, memory.getValue("surfaceflinger"), 0.01)
    }

    @Test fun `the permission summary names granted sensitive permissions once`() {
        val requested = listOf(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.RECORD_AUDIO",
        )
        assertEquals(listOf("Camera", "Location"), AppsInsights.grantedSensitive(requested, listOf(true, true, true, true, false)))
    }

    @Test fun `used data lately is above a megabyte`() {
        assertTrue(AppsInsights.usedData(2_500_000.0))
        assertFalse(AppsInsights.usedData(50_000.0))
        assertFalse(AppsInsights.usedData(null))
    }
}
