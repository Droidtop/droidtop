package dev.droidtop.runtime.requirements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The requirement model's rules (docs/SPEC.md "Permissions", §7 and §8; Droidtop/tracker#830 R1). */
class RequirementsTest {
    private fun state(id: String, met: Boolean) = RequirementState(AndroidAccess.all.first { it.id == id }, met)

    @Test fun `only a changed or new state is logged`() {
        val after = listOf(state(AndroidAccess.OVERLAY, true), state(AndroidAccess.USAGE, false), state(AndroidAccess.HOME, false))
        val before = mapOf(AndroidAccess.OVERLAY to true, AndroidAccess.USAGE to true)
        assertEquals(listOf(AndroidAccess.USAGE to false, AndroidAccess.HOME to false), Requirements.changes(before, after))
    }

    @Test fun `To do lists what is not met, in order`() {
        val all = listOf(state(AndroidAccess.OVERLAY, true), state(AndroidAccess.USAGE, false), state(AndroidAccess.HOME, false))
        assertEquals(listOf(AndroidAccess.USAGE, AndroidAccess.HOME), Requirements.toDo(all).map { it.requirement.id })
    }

    @Test fun `every requirement has a stable id of its own and a fix`() {
        val ids = AndroidAccess.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(AndroidAccess.all.all { it.fix != Requirement.Route.None && it.exact.isNotBlank() })
    }

    @Test fun `the words say what does not work yet, never Needs, and fit a row`() {
        // §7 "One wording": "<Thing> can't <verb> yet", never "Needs …"; titles keep the copy budget's 40 characters.
        val strings = Regex("<string name=\"(requirement_[a-z_]+)\">([^<]*)</string>")
            .findAll(File("src/main/res/values/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
        val missing = strings.filterKeys { it.endsWith("_missing") }
        assertEquals(AndroidAccess.all.size, missing.size)
        missing.forEach { (name, text) ->
            assertTrue(name, text.contains("can't") && text.endsWith("yet") && !text.contains("Needs") && text.length <= 40)
        }
        strings.filterKeys { it.endsWith("_allowed") }.forEach { (name, text) -> assertTrue(name, !text.contains("Needs") && text.length <= 40) }
    }
}
