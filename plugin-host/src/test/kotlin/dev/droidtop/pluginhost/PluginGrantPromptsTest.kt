package dev.droidtop.pluginhost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The first-use sheet's one surface (docs/plugin-api.md 4.3, Droidtop/tracker#128): opened per question, Not now when it cannot be. */
class PluginGrantPromptsTest {
    private val request = GrantPromptRequest("acme.one", "Acme", "net.any", "Use any website", "To fetch covers", PermissionTier.DANGEROUS)

    @After
    fun noSurface() = PluginGrantPrompts.setSurface(null)

    @Test
    fun `no surface answers Not now without waiting`() {
        PluginGrantPrompts.setSurface(null)
        assertEquals(GrantAnswer.NOT_NOW, PluginGrantPrompts.ask(request, timeoutMs = 60_000L))
        assertNull(PluginGrantPrompts.pending.value)
    }

    @Test
    fun `a surface that cannot open answers Not now`() {
        PluginGrantPrompts.setSurface { false }
        assertEquals(GrantAnswer.NOT_NOW, PluginGrantPrompts.ask(request, timeoutMs = 60_000L))
        PluginGrantPrompts.setSurface { throw IllegalStateException("no window") }
        assertEquals(GrantAnswer.NOT_NOW, PluginGrantPrompts.ask(request, timeoutMs = 60_000L))
        assertNull(PluginGrantPrompts.pending.value)
    }

    @Test
    fun `the surface sees the question and its answer is the call's`() {
        var opened = 0
        PluginGrantPrompts.setSurface {
            opened++
            val pending = PluginGrantPrompts.pending.value!!
            assertEquals("net.any", pending.request.permission)
            Thread { PluginGrantPrompts.answer(pending, GrantAnswer.ALLOW) }.start()
            true
        }
        assertEquals(GrantAnswer.ALLOW, PluginGrantPrompts.ask(request, timeoutMs = 10_000L))
        assertEquals(1, opened)
        assertNull("the question is gone once answered", PluginGrantPrompts.pending.value)
    }

    @Test
    fun `nobody answering is Not now after the wait`() {
        PluginGrantPrompts.setSurface { true }
        assertEquals(GrantAnswer.NOT_NOW, PluginGrantPrompts.ask(request, timeoutMs = 50L))
        assertNull(PluginGrantPrompts.pending.value)
    }
}
