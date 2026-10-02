package dev.droidtop.library.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of "Found for you" (docs/SPEC.md 12a "Plugin repositories"). */
class PluginRepoDetectionTest {
    private val json = """[
        {"full_name":"me/tagged","private":true,"archived":false,"topics":["droidtop-plugin","x"]},
        {"full_name":"me/droidtop-plugin-thing","private":false,"archived":false,"topics":[]},
        {"full_name":"org/plugins-old","private":false,"archived":true,"topics":[]},
        {"full_name":"me/notes","private":false,"archived":false,"topics":["notes"]},
        {"bad":1}
    ]"""

    @Test
    fun reposAreParsedAndJunkIsDropped() {
        val repos = PluginRepoDetection.parseRepos(json)
        assertEquals(listOf("me/tagged", "me/droidtop-plugin-thing", "org/plugins-old", "me/notes"), repos.map { it.repo })
        assertTrue(repos[0].isPrivate)
        assertTrue(PluginRepoDetection.parseRepos("nope").isEmpty())
    }

    @Test
    fun theTopicIsAMatchAndANameMatchEarnsOneReleaseRequest() {
        val repos = PluginRepoDetection.parseRepos(json).associateBy { it.repo }
        assertTrue(PluginRepoDetection.hasTopic(repos.getValue("me/tagged")))
        assertFalse(PluginRepoDetection.worthReleaseCheck(repos.getValue("me/tagged")))
        assertTrue(PluginRepoDetection.worthReleaseCheck(repos.getValue("me/droidtop-plugin-thing")))
        assertFalse(PluginRepoDetection.worthReleaseCheck(repos.getValue("org/plugins-old")))
        assertFalse(PluginRepoDetection.worthReleaseCheck(repos.getValue("me/notes")))
    }
}
