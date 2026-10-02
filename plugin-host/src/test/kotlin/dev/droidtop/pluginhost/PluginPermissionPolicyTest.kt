package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginPermissionPolicyTest {
    private val request = PluginPermissionRequest("network", "net.request", GrantState.ASK)

    @Test
    fun `all category and call combinations resolve by specificity`() {
        for (category in GrantState.entries) for (call in GrantState.entries) {
            assertEquals(call, PluginPermissionPolicy.resolve(request, mapOf(
                PluginPermissionPolicy.categoryKey(request.category) to category,
                PluginPermissionPolicy.callKey(request.call) to call,
            )))
        }
    }

    @Test
    fun `category choice wins when no call choice exists`() {
        for (category in GrantState.entries) assertEquals(category, PluginPermissionPolicy.resolve(
            request, mapOf(PluginPermissionPolicy.categoryKey("network") to category),
        ))
    }

    @Test
    fun `declared default is used when neither level has a choice`() {
        for (default in GrantState.entries) assertEquals(default, PluginPermissionPolicy.resolve(request.copy(default = default), emptyMap()))
    }

    @Test
    fun `a call choice overrides a category deny`() {
        assertEquals(GrantState.ASK, PluginPermissionPolicy.resolve(request, mapOf(
            PluginPermissionPolicy.categoryKey("network") to GrantState.DENIED,
            PluginPermissionPolicy.callKey("net.request") to GrantState.ASK,
        )))
    }
}
