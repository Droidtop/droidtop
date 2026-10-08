package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginCrashPolicyTest {
    private fun disabled(inFlight: Set<String>): List<String> {
        val out = mutableListOf<String>()
        PluginCrashPolicy.disableInFlight(inFlight) { out += it }
        return out
    }

    @Test
    fun `process death with one call in flight disables only that plugin`() {
        assertEquals(listOf("a.plugin"), disabled(setOf("a.plugin")))
    }

    @Test
    fun `process death with two calls in flight disables both`() {
        assertEquals(setOf("a.plugin", "b.plugin"), disabled(setOf("a.plugin", "b.plugin")).toSet())
    }

    @Test
    fun `an idle process death disables nothing`() {
        assertEquals(emptyList<String>(), disabled(emptySet()))
    }
}
