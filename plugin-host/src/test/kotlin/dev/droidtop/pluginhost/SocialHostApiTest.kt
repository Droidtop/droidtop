package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `social.changed` (docs/plugin-api.md 3 C19, Droidtop/tracker#327): only a social provider whose point is on may say so. */
class SocialHostApiTest {
    private fun request(args: JSONObject = JSONObject()) =
        obj("api" to "social", "version" to 1, "op" to "changed", "args" to args).toString()

    private fun reply(text: String) = PluginReply.parse(text)

    private val provider = TestPlugins.record(
        TestPlugins.manifest(id = "acme.chat", label = "Acme Chat") { it.put("provides", arr(obj("point" to "social.provider", "label" to "Acme"))) },
    )

    @Test
    fun `the point is in the registry, rated medium, so it is a line on the approval list`() {
        val point = ExtensionPoints.find("social.provider")!!
        assertEquals(PointRisk.MEDIUM, point.risk)
        assertTrue(point.lets.isNotBlank())
        assertTrue(ExtensionPoints.declares(provider.manifest, "social.provider"))
        assertTrue("a provider can never take over the host's own api", HostApis.isHostApi("social"))
    }

    @Test
    fun `a declared provider's change is handed to droidtop`() {
        val env = FakeEnv(provider)
        val args = obj("friendId" to "2", "message" to obj("key" to "m1", "text" to "hi", "timeMs" to 5L))
        val r = reply(BrokerCore("acme.chat", env).call(request(args)))
        assertTrue(r.ok)
        assertTrue(r.data.getBoolean("accepted"))
        assertEquals("acme.chat", env.socialChanges.single().first)
        assertEquals("2", env.socialChanges.single().second.getString("friendId"))
    }

    @Test
    fun `a plugin that does not provide the point is refused and droidtop hears nothing`() {
        val other = TestPlugins.record(TestPlugins.manifest(id = "acme.other"))
        val env = FakeEnv(other)
        val r = reply(BrokerCore("acme.other", env).call(request()))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, r.code)
        assertTrue(env.socialChanges.isEmpty())
    }

    @Test
    fun `a provider whose point the person switched off is refused`() {
        val env = FakeEnv(provider)
        env.setGrant("acme.chat", PluginPermissions.PROVIDE_PREFIX + "social.provider", GrantState.DENIED)
        val r = reply(BrokerCore("acme.chat", env).call(request()))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, r.code)
        assertTrue(env.socialChanges.isEmpty())
    }

    @Test
    fun `a change that names a conversation or a message wrongly is refused as invalid`() {
        val env = FakeEnv(provider)
        val core = BrokerCore("acme.chat", env)
        assertEquals(PluginErrorCode.INVALID_ARGS, reply(core.call(request(obj("friendId" to "  ")))).code)
        assertEquals(PluginErrorCode.INVALID_ARGS, reply(core.call(request(obj("message" to "hi")))).code)
        assertTrue(env.socialChanges.isEmpty())
    }

    @Test
    fun `a plugin that is turned off cannot say anything`() {
        val off = TestPlugins.record(provider.manifest, enabled = false)
        val env = FakeEnv(off)
        assertEquals(PluginErrorCode.PERMISSION_DENIED, reply(BrokerCore("acme.chat", env).call(request())).code)
        assertTrue(env.socialChanges.isEmpty())
    }
}
