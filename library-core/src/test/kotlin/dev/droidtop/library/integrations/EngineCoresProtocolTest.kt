package dev.droidtop.library.integrations

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/plugin-api.md 3 A7b (Droidtop/tracker#752): Enginehost's core list read into what decides a game's runner. */
class EngineCoresProtocolTest {
    private val reply = JSONObject(
        """
        {"catalogCheckedAtMs": 1761750000000, "cores": [
          {"id": "dev.enginehost.renpy.8_3.v1", "engine": "renpy", "label": "Ren'Py 8.3", "contexts": ["standard"],
           "engineVersions": {"series": ["8.3"], "any": false}, "rows": ["renpy"],
           "installed": false, "availableVersion": "1.0.15", "updateAvailable": false},
          {"id": "dev.enginehost.renpy.8_2.v1", "engine": "renpy", "label": "Ren'Py 8.2",
           "engineVersions": {"series": ["8.2"], "any": false}, "rows": ["renpy"],
           "installed": true, "installedVersion": "1.0.9", "availableVersion": "1.0.10", "updateAvailable": true},
          {"id": "dev.enginehost.kirikiri.v1", "engine": "kirikiri", "label": "KiriKiri",
           "engineVersions": {"any": true}, "rows": ["kirikiri"], "installed": false, "availableVersion": "2.0.0"},
          {"id": "dev.enginehost.renpy.8_3.v1", "engine": "renpy", "label": "a repeat, ignored", "rows": ["renpy"]},
          {"engine": "no id, ignored"}
        ]}
        """.trimIndent(),
    )

    @Test
    fun `the list reads every core once, with what is installed and what can be`() {
        val answer = EngineCoresProtocol.answer(reply)
        assertEquals(3, answer.cores.size)
        val installable = answer.cores[0]
        assertEquals("Ren'Py 8.3", installable.label)
        assertFalse(installable.installed)
        assertNull(installable.installedVersion)
        assertEquals("1.0.15", installable.availableVersion)
        val installed = answer.cores[1]
        assertTrue(installed.installed)
        assertEquals("1.0.9", installed.installedVersion)
        assertTrue(installed.updateAvailable)
        assertTrue(answer.cores[2].anyVersion)
        assertEquals(1761750000000L, answer.catalogCheckedAtMs)
    }

    @Test
    fun `a game's version picks the core of its series, installed or installable`() {
        val answers = listOf(EngineCoresProtocol.answer(reply))
        val v83 = EngineCoresProtocol.verdict(answers, "renpy", "8.3.4")
        assertTrue(v83 is CoreVerdict.Installable)
        assertEquals("Ren'Py 8.3", (v83 as CoreVerdict.Installable).core.label)
        val v82 = EngineCoresProtocol.verdict(answers, "renpy", "8.2.1")
        assertTrue(v82 is CoreVerdict.Installed)
        // A version not known yet matches the row alone, and an installed core wins.
        assertTrue(EngineCoresProtocol.verdict(answers, "renpy", null) is CoreVerdict.Installed)
        // A core for any version takes every one.
        assertTrue(EngineCoresProtocol.verdict(answers, "kirikiri", "2.32") is CoreVerdict.Installable)
    }

    @Test
    fun `none is said only when a checked catalog has nothing, otherwise the list does not decide`() {
        val checked = listOf(EngineCoresProtocol.answer(reply))
        assertEquals(CoreVerdict.None, EngineCoresProtocol.verdict(checked, "renpy", "7.4.11"))
        assertEquals(CoreVerdict.None, EngineCoresProtocol.verdict(checked, "godot", null))
        val neverChecked = listOf(EngineCoresProtocol.answer(JSONObject(reply.toString()).put("catalogCheckedAtMs", 0)))
        assertNull(EngineCoresProtocol.verdict(neverChecked, "godot", null))
        assertNull(EngineCoresProtocol.verdict(emptyList(), "renpy", "8.3.0"))
    }

    @Test
    fun `what is kept on disk reads back the same`() {
        val answer = EngineCoresProtocol.answer(reply)
        assertEquals(answer, EngineCoresProtocol.answer(EngineCoresProtocol.toJson(answer)))
    }
    @Test
    fun `a list is kept while its plugin cannot be asked, and dropped only when it is removed or stops providing`() {
        // Droidtop/tracker#854: a list dropped while Enginehost could not be asked offered "Get … support" for an installed core.
        assertTrue(EngineCoresProtocol.keepsUnasked(installed = true, declares = true))
        assertTrue(EngineCoresProtocol.keepsUnasked(installed = true, declares = null))
        assertFalse(EngineCoresProtocol.keepsUnasked(installed = true, declares = false))
        assertFalse(EngineCoresProtocol.keepsUnasked(installed = false, declares = null))
    }
}
