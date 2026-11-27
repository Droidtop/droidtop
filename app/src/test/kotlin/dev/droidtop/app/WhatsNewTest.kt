package dev.droidtop.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** What's new reads the changelog.json CI bundles and picks the builds since the last one seen (docs/SPEC.md 7b). */
class WhatsNewTest {
    private val json = """
        {"formatVersion": 1, "builds": [
          {"versionCode": 2302, "versionName": "0.2.0-dev.2302", "fragments": ["c"],
           "sections": {"Fixed": ["A fix"], "Added": ["A feature", "Another"]}},
          {"versionCode": 2301, "versionName": "0.2.0-dev.2301", "fragments": ["b"], "sections": {"Changed": ["A change"]}},
          {"versionCode": 2300, "versionName": "0.2.0-dev.2300", "fragments": ["a"], "sections": {"Added": ["The first"]}}
        ]}
    """.trimIndent()

    @Test
    fun `a build's sections come in Added, Changed, Fixed order`() {
        val newest = WhatsNew.parse(json).first()
        assertEquals("0.2.0-dev.2302", newest.versionName)
        assertEquals(listOf("Added", "Fixed"), newest.sections.map { it.first })
        assertEquals(listOf("A feature", "Another"), newest.sections.first().second)
    }

    @Test
    fun `an update shows every build since the one last seen, skipped ones included`() {
        val since = WhatsNew.since(WhatsNew.parse(json), lastSeen = 2300, current = 2302)
        assertEquals(listOf(2302L, 2301L), since.map { it.versionCode })
    }

    @Test
    fun `an update from before What's new shows only the build installed`() {
        val since = WhatsNew.since(WhatsNew.parse(json), lastSeen = null, current = 2302)
        assertEquals(listOf(2302L), since.map { it.versionCode })
    }

    @Test
    fun `a build newer than the one installed is never shown`() {
        val since = WhatsNew.since(WhatsNew.parse(json), lastSeen = 2300, current = 2301)
        assertEquals(listOf(2301L), since.map { it.versionCode })
    }

    @Test
    fun `what to do after updating is every build's, oldest first, each line once`() {
        val builds = WhatsNew.parse(
            """
            {"formatVersion": 1, "builds": [
              {"versionCode": 3, "versionName": "c", "sections": {}, "onboarding": ["Sign in to TorBox again", "Turn on Y"]},
              {"versionCode": 2, "versionName": "b", "sections": {"Fixed": ["x"]}},
              {"versionCode": 1, "versionName": "a", "sections": {}, "onboarding": ["Turn on Y", ""]}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("Turn on Y"), builds.last().onboarding)
        assertEquals(listOf("Turn on Y", "Sign in to TorBox again"), WhatsNew.onboarding(builds))
        assertEquals(emptyList<String>(), WhatsNew.onboarding(WhatsNew.parse(json)))
    }

    @Test
    fun `over the shell it waits for the placement to settle, over other screens it does not`() {
        assertEquals(false, WhatsNew.mayOpen(screenAlive = true, isShell = true, settled = false))
        assertEquals(true, WhatsNew.mayOpen(screenAlive = true, isShell = true, settled = true))
        assertEquals(true, WhatsNew.mayOpen(screenAlive = true, isShell = false, settled = false))
        assertEquals(false, WhatsNew.mayOpen(screenAlive = false, isShell = true, settled = true))
    }

    @Test
    fun `quiet builds in a row are one entry with the generic line, and old wording reads it too`() {
        val builds = WhatsNew.parse(
            """
            {"formatVersion": 1, "builds": [
              {"versionCode": 2306, "versionName": "0.2.0-dev.2306", "sections": {"Changed": ["Various bug fixes and performance improvements"]}},
              {"versionCode": 2305, "versionName": "0.2.0-dev.2305", "sections": {"Changed": ["Internal changes: 3. Nothing looks different"]}},
              {"versionCode": 2304, "versionName": "0.2.0-dev.2304", "sections": {}},
              {"versionCode": 2303, "versionName": "0.2.0-dev.2303", "sections": {"Fixed": ["A fix"], "Changed": ["Internal changes: 2. Nothing looks different"]}},
              {"versionCode": 2302, "versionName": "0.2.0-dev.2302", "sections": {"Changed": ["Internal changes (1); nothing looks different"]}}
            ]}
            """.trimIndent(),
        )
        val shown = WhatsNew.condense(builds)
        assertEquals(listOf("0.2.0-dev.2304 to 0.2.0-dev.2306", "0.2.0-dev.2303", "0.2.0-dev.2302"), shown.map { it.versionName })
        assertEquals(listOf("Changed" to listOf(WhatsNew.QUIET_LINE)), shown[0].sections)
        assertEquals(listOf("Changed" to listOf(WhatsNew.QUIET_LINE), "Fixed" to listOf("A fix")), shown[1].sections)
        assertEquals(listOf("Changed" to listOf(WhatsNew.QUIET_LINE)), shown[2].sections)
    }

    @Test
    fun `a quiet build with something to do after it keeps its own entry`() {
        val quiet = ChangelogBuild(2, "b", listOf("Changed" to listOf(WhatsNew.QUIET_LINE)), onboarding = listOf("Sign in again"))
        val other = ChangelogBuild(1, "a", listOf("Changed" to listOf(WhatsNew.QUIET_LINE)))
        assertEquals(listOf("b", "a"), WhatsNew.condense(listOf(quiet, other)).map { it.versionName })
    }
}
