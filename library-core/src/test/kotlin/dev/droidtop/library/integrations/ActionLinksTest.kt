package dev.droidtop.library.integrations

import java.net.URLEncoder
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** droidtop:// action links, grammar 2 (docs/SPEC.md 12a "Action links", Droidtop/tracker#459): literal calls, refused whole on any bad one. */
class ActionLinksTest {
    private val lookup: (String) -> ActionLinks.Spec? = { ActionLinks.hostOps[it] }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    private val catalog = "https://github.com/gamegrab-sources/catalog"
    private val key = "ab".repeat(32)

    private val add = """catalog.add({"address":"$catalog"})"""
    private val trust = """key.trust({"catalog":"$catalog","origin":"bi0shacker001","sha256":"$key"})"""
    private val install = """plugin.install({"catalog":"$catalog","id":"bi0shacker001.romgi-3ds-decrypt"})"""

    private fun ok(link: String) = ActionLinks.parse(link, lookup) as ActionLinks.Parsed.Ok
    private fun refused(link: String) = (ActionLinks.parse(link, lookup) as ActionLinks.Parsed.Refused).reason

    @Test
    fun `literal calls joined with ampersands, percent-encoded, read in order with inferred dependencies`() {
        val steps = ok("droidtop://call?v=2&${enc(add)}&${enc(trust)}&${enc(install)}").steps
        assertEquals(listOf("catalog.add", "key.trust", "plugin.install"), steps.map { it.spec.id })
        assertEquals(catalog, steps[0].params["address"])
        assertEquals("bi0shacker001", steps[1].params["origin"])
        assertEquals(setOf(1), steps[1].dependsOn)
        assertEquals(setOf(1, 2), steps[2].dependsOn)
    }

    @Test
    fun `the same calls unencoded, as one base64 list, and as single base64 calls read the same`() {
        val expected = ok("droidtop://call?v=2&${enc(add)}&${enc(trust)}&${enc(install)}").steps.map { it.spec.id to it.params }
        assertEquals(expected, ok("droidtop://call?v=2&b64:${b64("$add&$trust&$install")}").steps.map { it.spec.id to it.params })
        assertEquals(expected, ok("droidtop://call?v=2&b64:${b64(add)}&${enc(trust)}&b64:${b64(install)}").steps.map { it.spec.id to it.params })
        assertEquals(expected, ok("https://droidtop.github.io/call?v=2&${enc(add)}&${enc(trust)}&${enc(install)}").steps.map { it.spec.id to it.params })
    }

    @Test
    fun `an ampersand or a parenthesis inside a JSON string belongs to the string`() {
        val address = "https://example.org/catalog?x=1&y=(2)"
        val steps = ok("droidtop://call?v=2&b64:${b64("""catalog.add({"address":"$address"})&plugin.install({"id":"a.b"})""")}").steps
        assertEquals(address, steps[0].params["address"])
        assertEquals(2, steps.size)
    }

    @Test
    fun `denying a call refuses every call that depends on it`() {
        val steps = ok("droidtop://call?v=2&b64:${b64("$add&$trust&$install&plugin.install({\"id\":\"droidtop.retroarch\"})")}").steps
        assertEquals(setOf(2, 3), ActionLinks.refused(steps, setOf(2)))
        assertEquals(setOf(1, 2, 3), ActionLinks.refused(steps, setOf(1)))
        assertEquals(setOf(4), ActionLinks.refused(steps, setOf(4)))
    }

    @Test
    fun `needs adds dependencies on earlier calls only and is not an argument`() {
        val steps = ok("droidtop://call?v=2&b64:${b64("plugin.install({\"id\":\"a.b\"})&plugin.install({\"id\":\"c.d\",\"\$needs\":[1]})")}").steps
        assertEquals(setOf(1), steps[1].dependsOn)
        assertFalse("\$needs" in steps[1].params)
        assertTrue(refused("droidtop://call?v=2&b64:${b64("plugin.install({\"id\":\"a.b\",\"\$needs\":[1]})")}").contains("does not come before"))
    }

    private fun refusedCalls(calls: String) = refused("droidtop://call?v=2&b64:${b64(calls)}")

    @Test
    fun `any bad call refuses the whole link`() {
        assertTrue(refused("droidtop://call?${enc(add)}").contains("v=2"))
        assertTrue(refused("droidtop://call?v=1&${enc(add)}").contains("v=2"))
        assertTrue(refusedCalls("$add&net.request({\"url\":\"https://x.org\"})").contains("not something a link can call"))
        assertTrue(refusedCalls("plugin.install({\"id\":\"a.b\",\"evil\":1})").contains("takes no"))
        assertTrue(refusedCalls("key.trust({\"catalog\":\"$catalog\",\"origin\":\"o\",\"sha256\":\"xyz\"})").contains("sha256"))
        assertTrue(refusedCalls("catalog.add({\"address\":\"http://example.org/x\"})").contains("address"))
        assertTrue(refusedCalls("key.trust({\"origin\":\"o\",\"sha256\":\"$key\"})").contains("needs \"catalog\""))
        assertTrue(refusedCalls("plugin.install({\"id\":\"a.b\"}").contains("not closed"))
        assertTrue(refusedCalls("plugin.install(not json)").contains("JSON"))
        assertTrue(refusedCalls("plugin.install({\"id\":\"a.b\"})x").contains("joined with &"))
        assertTrue(refusedCalls("b64:${b64(add)}").contains("another base64"))
        assertTrue(refused("droidtop://call?v=2&b64:***").contains("base64"))
        assertTrue(refused("droidtop://call?v=2").contains("asks for nothing"))
        val many = (1..11).joinToString("&") { "plugin.install({\"id\":\"a.b$it\"})" }
        assertTrue(refusedCalls(many).contains("more than"))
    }

    @Test
    fun `the older catalog links are read as one call`() {
        assertEquals("catalog.add", ok("droidtop://add-catalog?address=${enc(catalog)}").steps.single().spec.id)
        assertEquals(catalog, ok("https://droidtop.github.io/add-catalog?address=${enc(catalog)}").steps.single().params["address"])
        val one = ok("droidtop://install-plugin?id=droidtop.retroarch").steps.single()
        assertEquals("plugin.install", one.spec.id)
        assertEquals("droidtop.retroarch", one.params["id"])
        assertEquals(catalog, ActionLinks.catalogAddress("droidtop://call?v=2&${enc(add)}"))
    }

    @Test
    fun `a plus sign in a value stays a plus sign`() {
        assertEquals("a+b", ActionLinks.percentDecode("a+b"))
        assertEquals("a b&", ActionLinks.percentDecode("a%20b%26"))
    }

    @Test
    fun `only droidtop's own links are action links`() {
        assertTrue(ActionLinks.isActionLink("droidtop://call?v=2"))
        assertFalse(ActionLinks.isActionLink("https://example.org/call?v=2"))
        assertFalse(ActionLinks.isActionLink("fdroidrepos://example.org/fdroid/repo"))
    }
}
