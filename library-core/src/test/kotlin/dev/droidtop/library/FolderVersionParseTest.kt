package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A folder game's version comes from its folder name by the one parse
 * ([GameTitleParser]) that also strips it off the title (docs/SPEC.md 7g,
 * Droidtop/tracker#397 slice E). The shapes the plan names, each a row;
 * anything else has no version.
 */
class FolderVersionParseTest {

    private val rows = listOf(
        // folder name, title, version
        Triple("Game v1.2", "Game", "1.2"),
        Triple("Game-1.2.0", "Game", "1.2.0"),
        Triple("Game [v0.5]", "Game", "0.5"),
        Triple("Game (0.5b)", "Game", "0.5b"),
        Triple("Game 1.2 PC", "Game", "1.2"),
        Triple("Game_v0.6.1a", "Game", "0.6.1a"),
        // Not versions: a sequel number, a year, a plain name (a bare "Game" is an engine folder name, 7n).
        Triple("Far Cry 5", "Far Cry 5", ""),
        Triple("Cyberpunk 2077", "Cyberpunk 2077", ""),
        Triple("Some Game", "Some Game", ""),
    )

    @Test
    fun `each recognised shape gives its version and the bare title`() {
        for ((name, title, version) in rows) {
            val parsed = GameTitleParser.parseName(name)
            assertEquals("version of $name", version, parsed.version)
            assertEquals("title of $name", title, parsed.title)
        }
    }

    @Test
    fun `the identity key is the title without its version`() {
        assertEquals(GameFolderIds.titleKey("MyGame-v0.5"), GameFolderIds.titleKey("MyGame-v0.6"))
        assertEquals(GameFolderIds.titleKey("Game [v0.5]"), GameFolderIds.titleKey("Game (0.6a)"))
        assertEquals("game", GameFolderIds.titleKey("Game 1.2 PC"))
    }
}
