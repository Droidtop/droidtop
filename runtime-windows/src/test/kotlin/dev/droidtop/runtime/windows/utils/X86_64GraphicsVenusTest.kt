package dev.droidtop.runtime.windows.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class X86_64GraphicsVenusTest {
    private fun version(major: Int, minor: Int) = (major shl 22) or (minor shl 12)

    @Test
    fun `Venus needs Vulkan 1_1, fd external memory and timeline semaphores`() {
        val fd = listOf("VK_KHR_external_memory_fd")
        assertTrue(X86_64Graphics.venusQualifies(version(1, 3), fd))
        assertTrue(X86_64Graphics.venusQualifies(version(1, 1), fd + "VK_KHR_timeline_semaphore"))
        assertFalse(X86_64Graphics.venusQualifies(version(1, 1), fd))
        assertFalse(X86_64Graphics.venusQualifies(version(1, 0), fd + "VK_KHR_timeline_semaphore"))
        assertFalse(X86_64Graphics.venusQualifies(version(1, 3), listOf("VK_KHR_timeline_semaphore")))
    }
}
