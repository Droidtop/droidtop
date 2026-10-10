package dev.droidtop.library.integrations

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** droidtop:// action links (docs/SPEC.md 12a "Action links", Droidtop/tracker#459): one grammar, refused whole on any bad call. */
class ActionLinksTest {
    private val lookup: (String) -> ActionLinks.Spec? = { ActionLinks.builtIns[it] }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val catalog = "https://github.com/gamegrab-sources/catalog"
    private val key = "ab".repeat(32)

    private fun ok(link: String) = ActionLinks.parse(link, lookup) as ActionLinks.Parsed.Ok
    private fun refused(link: String) = (ActionLinks.parse(link, lookup) as ActionLinks.Parsed.Refused).reason

    @Test
    fun `a link carries its steps in order, each with its own parameters`() {
        val steps = ok(
            "droidtop://do?v=1&a=catalog.add&address=${enc(catalog)}" +
                "&a=key.trust&catalog=${enc(catalog)}&origin=bi0shacker001&sha256=$key" +
                "&a=plugin.install&catalog=${enc(catalog)}&id=bi0shacker001.romgi-3ds-decrypt",
        ).steps
        assertEquals(listOf("catalog.add", "key.trust", "plugin.install"), steps.map { it.spec.id })
        assertEquals(catalog, steps[0].params["address"])
        assertEquals("bi0shacker001", steps[1].params["origin"])
        // The trust needs the catalog it is added by; the install needs both.
        assertEquals(setOf(1), steps[1].dependsOn)
        assertEquals(setOf(1, 2), steps[2].dependsOn)
    }

    @Test
    fun `the https form is the same link`() {
        val steps = ok("https://droidtop.github.io/do?v=1&a=plugin.install&id=droidtop.retroarch").steps
        assertEquals("droidtop.retroarch", steps.single().params["id"])
        assertTrue(steps.single().dependsOn.isEmpty())
    }

    @Test
    fun `denying a step refuses every step that depends on it, directly or not`() {
        val steps = ok(
            "droidtop://do?v=1&a=catalog.add&address=${enc(catalog)}" +
                "&a=key.trust&catalog=${enc(catalog)}&origin=o&sha256=$key" +
                "&a=plugin.install&catalog=${enc(catalog)}&id=o.p&a=plugin.install&id=droidtop.retroarch",
        ).steps
        assertEquals(setOf(2, 3), ActionLinks.refused(steps, setOf(2)))
        assertEquals(setOf(1, 2, 3), ActionLinks.refused(steps, setOf(1)))
        assertEquals(setOf(4), ActionLinks.refused(steps, setOf(4)))
    }

    @Test
    fun `needs adds dependencies on earlier steps only`() {
        val steps = ok("droidtop://do?v=1&a=plugin.install&id=a.b&a=plugin.install&id=c.d&needs=1").steps
        assertEquals(setOf(1), steps[1].dependsOn)
        assertTrue(refused("droidtop://do?v=1&a=plugin.install&id=a.b&needs=1").contains("does not come before"))
    }

    @Test
    fun `any bad call refuses the whole link`() {
        assertTrue(refused("droidtop://do?a=plugin.install&id=a.b").contains("v=1"))
        assertTrue(refused("droidtop://do?v=2&a=plugin.install&id=a.b").contains("v=1"))
        assertTrue(refused("droidtop://do?v=1&a=plugin.install&id=a.b&a=nothing.here").contains("no action"))
        assertTrue(refused("droidtop://do?v=1&a=plugin.install&id=a.b&evil=1").contains("takes no"))
        assertTrue(refused("droidtop://do?v=1&a=plugin.install&id=a.b&id=c.d").contains("twice"))
        assertTrue(refused("droidtop://do?v=1&a=key.trust&catalog=${enc(catalog)}&origin=o&sha256=xyz").contains("sha256"))
        assertTrue(refused("droidtop://do?v=1&a=catalog.add&address=${enc("http://example.org/x")}").contains("address"))
        assertTrue(refused("droidtop://do?v=1&a=key.trust&origin=o&sha256=$key").contains("needs \"catalog\""))
        assertTrue(refused("droidtop://do?v=1&id=a.b").contains("before any action"))
        assertTrue(refused("droidtop://do?v=1").contains("asks for nothing"))
        val many = (1..11).joinToString("") { "&a=plugin.install&id=a.b$it" }
        assertTrue(refused("droidtop://do?v=1$many").contains("more than"))
    }

    @Test
    fun `the older catalog links are read as one step`() {
        assertEquals("catalog.add", ok("droidtop://add-catalog?address=${enc(catalog)}").steps.single().spec.id)
        assertEquals(catalog, ok("https://droidtop.github.io/add-catalog?address=${enc(catalog)}").steps.single().params["address"])
        val install = ok("droidtop://install-plugin?id=droidtop.retroarch").steps.single()
        assertEquals("plugin.install", install.spec.id)
        assertEquals("droidtop.retroarch", install.params["id"])
        assertEquals(catalog, ActionLinks.catalogAddress("droidtop://do?v=1&a=catalog.add&address=${enc(catalog)}"))
    }

    @Test
    fun `only droidtop's own links are action links`() {
        assertTrue(ActionLinks.isActionLink("droidtop://do?v=1"))
        assertFalse(ActionLinks.isActionLink("https://example.org/do?v=1"))
        assertFalse(ActionLinks.isActionLink("fdroidrepos://example.org/fdroid/repo"))
    }
}
