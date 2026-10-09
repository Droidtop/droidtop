package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/plugin-api.md 4.1, "Android permissions": declared by the plugin, held and asked for by droidtop, gated per plugin. */
class AndroidPermissionsTest {
    private fun manifest(vararg permissions: JSONObject, id: String = "acme.chat") =
        TestPlugins.manifest(id = id) { it.put("permissions", JSONArray(permissions.toList())) }

    @Test
    fun `an android name is read as the host permission of the operation that uses it`() {
        val m = manifest(obj("id" to "android.permission.POST_NOTIFICATIONS", "reason" to "Tells you when a friend writes"))
        val declared = m.v2.permissions.single()
        assertEquals("notify.post", declared.id)
        assertEquals("Tells you when a friend writes", declared.reason)
        assertEquals("android.permission.POST_NOTIFICATIONS", AndroidPermissions.declaredName(declared))
        // It round-trips as the host permission and keeps the name it was written under.
        val again = DeclaredPermission.fromJson(declared.toJson())!!
        assertEquals(declared, again)
    }

    @Test
    fun `a permission and its android name are one permission`() {
        val m = manifest(obj("id" to "notify.post"), obj("id" to "android.permission.POST_NOTIFICATIONS"))
        assertEquals(listOf("notify.post"), m.v2.permissions.map { it.id })
    }

    @Test
    fun `an android permission droidtop does not hold is refused at install`() {
        val held = setOf("android.permission.POST_NOTIFICATIONS", "android.permission.INTERNET")
        assertEquals(emptyList<String>(), AndroidPermissions.installProblems(manifest(obj("id" to "android.permission.POST_NOTIFICATIONS")), held))
        val camera = AndroidPermissions.installProblems(manifest(obj("id" to "android.permission.CAMERA")), held)
        assertTrue(camera.single().contains("CAMERA"))
        assertTrue("a host id names no Android permission of its own", AndroidPermissions.installProblems(manifest(obj("id" to "notify.post")), emptySet()).isEmpty())
    }

    @Test
    fun `one droidtop holds but no operation uses is not supported, not granted`() {
        val m = manifest(obj("id" to "android.permission.RECORD_AUDIO"))
        assertEquals("android.permission.RECORD_AUDIO", m.v2.permissions.single().id)
        assertTrue(m.unsupportedDeclarations().contains("permission android.permission.RECORD_AUDIO"))
    }

    @Test
    fun `droidtop asks android only during a call the person started, then posts`() {
        val record = TestPlugins.record(manifest(obj("id" to "android.permission.POST_NOTIFICATIONS")))
        val env = FakeEnv(record)
        env.androidHeld = false
        val call = obj("api" to "notify", "version" to 1, "op" to "post", "args" to obj("title" to "Ann", "text" to "hi")).toString()
        val background = PluginReply.parse(BrokerCore("acme.chat", env).call(call))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, background.code)
        assertTrue(env.androidAsks.isEmpty())
        assertTrue(env.notifications.isEmpty())
        env.user = true
        env.androidAnswer = true
        val asked = PluginReply.parse(BrokerCore("acme.chat", env).call(call))
        assertTrue(asked.message, asked.ok)
        assertEquals("android.permission.POST_NOTIFICATIONS", env.androidAsks.single().permission)
        assertEquals(listOf(Triple("acme.chat", "Ann", "hi")), env.notifications)
    }

    @Test
    fun `notifications are limited per plugin per hour`() {
        // Its own id: the quota is droidtop's, kept across calls, and no other test may have used it.
        val env = FakeEnv(TestPlugins.record(manifest(obj("id" to "notify.post"), id = "acme.quota")))
        val broker = BrokerCore("acme.quota", env)
        val call = obj("api" to "notify", "version" to 1, "op" to "post", "args" to obj("title" to "x")).toString()
        repeat(HostApis.NOTIFY_PER_HOUR) { assertTrue(PluginReply.parse(broker.call(call)).ok) }
        assertEquals(PluginErrorCode.RATE_LIMITED, PluginReply.parse(broker.call(call)).code)
        env.now += 60L * 60 * 1000
        assertTrue(PluginReply.parse(broker.call(call)).ok)
    }
}
