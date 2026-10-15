package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `systems` a `library.sources` entry declares and how a Get games page reads it (docs/plugin-api.md 1.6, Droidtop/tracker#543). */
class SourceSystemsTest {
    private fun entry(systems: Any?): ProvidedPoint {
        val json = JSONObject().put("point", "library.sources").put("version", 1)
        if (systems != null) json.put("systems", systems)
        return ProvidedPoint.fromJson(json)!!
    }

    @Test
    fun `an entry without systems keeps the old behaviour and serves everything`() {
        val none = SourceSystems.of(entry(null))
        assertFalse(none.isDeclared)
        assertTrue(none.serves("gba"))
        assertTrue(none.serves("pc"))
        assertTrue(none.serves(null))
    }

    @Test
    fun `a malformed or empty value is undeclared, never a source that serves nothing`() {
        assertFalse(SourceSystems.of(entry(JSONArray())).isDeclared)
        assertFalse(SourceSystems.of(entry(7)).isDeclared)
        assertFalse(SourceSystems.of(entry(JSONArray(listOf("", "  ")))).isDeclared)
    }

    @Test
    fun `a star serves every system`() {
        val star = SourceSystems.of(entry("*"))
        assertTrue(star.isDeclared)
        assertTrue(star.serves("gba"))
        assertTrue(star.serves("pc"))
        assertTrue(SourceSystems.of(entry(JSONArray(listOf("windows", "*")))).serves("snes"))
    }

    @Test
    fun `a named list serves exactly those systems and the PC library`() {
        val f95 = SourceSystems.of(entry(JSONArray(listOf("windows", "linux"))))
        assertTrue(f95.serves("windows"))
        assertTrue(f95.serves("Linux"))
        assertFalse(f95.serves("gba"))
        assertTrue(f95.serves("pc"))
        // A PC family system the entry does not name is not served: DOS is not Windows.
        assertFalse(f95.serves("dos"))
        assertTrue(f95.servesOnlyPc())
    }

    @Test
    fun `a console source is not asked by the PC library`() {
        val psp = SourceSystems.of(entry(JSONArray(listOf("psp", "psvita"))))
        assertTrue(psp.serves("psp"))
        assertFalse(psp.serves("pc"))
        assertFalse(psp.servesOnlyPc())
    }

    @Test
    fun `a source naming pc serves every PC system`() {
        val pc = SourceSystems.of(entry(JSONArray(listOf("pc"))))
        assertTrue(pc.serves("windows"))
        assertTrue(pc.serves("dos"))
        assertFalse(pc.serves("snes"))
    }

    @Test
    fun `a blank system is a search with no system of its own and asks every source`() {
        val f95 = SourceSystems.of(entry(JSONArray(listOf("windows"))))
        assertTrue(f95.serves(null))
        assertTrue(f95.serves(" "))
    }

    @Test
    fun `systems survive the manifest round trip`() {
        val back = ProvidedPoint.fromJson(entry(JSONArray(listOf("dos", "windows3x"))).toJson())!!
        assertEquals(setOf("dos", "windows3x"), SourceSystems.of(back).named)
    }
}
