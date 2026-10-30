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
}
