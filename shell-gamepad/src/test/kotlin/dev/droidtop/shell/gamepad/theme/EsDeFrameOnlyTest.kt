package dev.droidtop.shell.gamepad.theme

import androidx.compose.ui.unit.dp
import dev.droidtop.library.theme.EsDeThemeElement
import dev.droidtop.library.theme.EsDeThemeView
import dev.droidtop.library.theme.EsDeThemeValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame-only render (docs/SPEC.md 7i): a game-bound element is the
 * theme's CONTENT, everything else is its FRAME, and the PC library keeps
 * the frame and draws its own content over it. The classification is the
 * one rule both sides read.
 */
class EsDeFrameOnlyTest {

    private fun element(type: String, vararg properties: Pair<String, EsDeThemeValue>) =
        EsDeThemeElement(type = type, key = "${type}_test", properties = properties.toMap())

    private fun image(imageType: String? = null, metadataElement: Boolean? = null, path: String? = null) = element(
        "image",
        *listOfNotNull(
            imageType?.let { "imageType" to EsDeThemeValue.Str(it) },
            metadataElement?.let { "metadataElement" to EsDeThemeValue.Bool(it) },
            path?.let { "path" to EsDeThemeValue.Path(it) },
        ).toTypedArray(),
    )

    @Test
    fun `the primary list widgets are content`() {
        listOf("carousel", "grid", "textlist").forEach {
            assertTrue("a $it is content", esDeElementBindsGame(element(it)))
        }
    }

    @Test
    fun `per-game metadata elements are content`() {
        listOf("rating", "badges", "datetime", "gameselector").forEach {
            assertTrue("a $it is content", esDeElementBindsGame(element(it)))
        }
        assertTrue(esDeElementBindsGame(element("text", "metadata" to EsDeThemeValue.Str("description"))))
        assertTrue(esDeElementBindsGame(image(imageType = "fanart")))
        assertTrue(esDeElementBindsGame(image(metadataElement = true)))
        assertTrue(esDeElementBindsGame(element("video", "imageType" to EsDeThemeValue.Str("miximage"))))
    }

    @Test
    fun `the frame is everything that does not move with the focused game`() {
        assertFalse(esDeElementBindsGame(image(path = "theme/logo.svg")))
        assertFalse(esDeElementBindsGame(element("text", "text" to EsDeThemeValue.Str("Some static words"))))
        assertFalse(esDeElementBindsGame(element("text", "systemdata" to EsDeThemeValue.Str("systemName"))))
        listOf("helpsystem", "clock", "systemstatus", "sound", "animation", "gamelistinfo").forEach {
            assertFalse("a $it is frame", esDeElementBindsGame(element(it)))
        }
    }

    @Test
    fun `the help slot reads the theme's own declarations and its defaults`() {
        val declared = EsDeThemeView(
            mapOf(
                "helpsystem_a" to element("helpsystem", "pos" to EsDeThemeValue.Pair(0.5f, 0.9f)),
                "helpsystem_b" to element("helpsystem", "origin" to EsDeThemeValue.Pair(0.5f, 0.5f)),
            ),
        )
        val none = EsDeThemeView(emptyMap())

        // Later declarations merge over earlier ones per property, the
        // same rule the renderer's own help bar applies.
        assertEquals(0.9f, declared.helpRowSlot(vertical = false).posY)
        assertEquals(0.5f, declared.helpRowSlot(vertical = false).originY)
        // No declaration at all: ES-DE's own component default.
        assertEquals(0.9515f, none.helpRowSlot(vertical = false).posY)
        assertEquals(0.975f, none.helpRowSlot(vertical = true).posY)
    }

    @Test
    fun `the content slot is the rect the theme's own list declared`() {
        val view = EsDeThemeView(
            mapOf(
                "grid_list" to element(
                    "grid",
                    "pos" to EsDeThemeValue.Pair(0.05f, 0.1f),
                    "size" to EsDeThemeValue.Pair(0.55f, 0.8f),
                    "origin" to EsDeThemeValue.Pair(0f, 0f),
                ),
            ),
        )

        val rect = view.declaredListRect(1280.dp, 720.dp)!!

        assertEquals(64.dp, rect.x)
        assertEquals(72.dp, rect.y)
        assertEquals(704.dp, rect.width)
        assertEquals(576.dp, rect.height)
    }

    @Test
    fun `a view that declares no list rect answers no proportion to inherit`() {
        assertNull(EsDeThemeView(emptyMap()).declaredListRect(1280.dp, 720.dp))
        assertNull(
            EsDeThemeView(mapOf("grid_list" to element("grid", "pos" to EsDeThemeValue.Pair(0.1f, 0.1f))))
                .declaredListRect(1280.dp, 720.dp),
        )
    }
}
