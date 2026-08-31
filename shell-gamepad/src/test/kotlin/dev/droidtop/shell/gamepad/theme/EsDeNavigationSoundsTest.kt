package dev.droidtop.shell.gamepad.theme

import dev.droidtop.library.theme.EsDeTheme
import dev.droidtop.library.theme.EsDeThemeElement
import dev.droidtop.library.theme.EsDeThemeValue
import dev.droidtop.library.theme.EsDeThemeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-model tests for the navigation-sound extraction and the animation
 * extension dispatch -- the parts of the sound/animation support that are
 * real logic without an Android dependency. The XML-parsing side itself
 * (that `<sound name="...">` under `<view name="all">` lands in both
 * views as a `sound_<name>` element) can't be JVM-unit-tested: the
 * parser is built on android.util.Xml, an Android-framework-only entry
 * point -- so these tests start from hand-built parsed models instead,
 * the same layer every renderer consumes.
 */
class EsDeNavigationSoundsTest {
    private fun soundElement(name: String, path: String) = EsDeThemeElement(
        type = "sound",
        key = "sound_$name",
        properties = mapOf("path" to EsDeThemeValue.Path(path)),
    )

    private fun theme(vararg views: Pair<String, List<EsDeThemeElement>>) = EsDeTheme(
        variables = emptyMap(),
        views = views.toMap().mapValues { (_, elements) ->
            EsDeThemeView(elements.associateBy { it.key })
        },
    )

    @Test
    fun `the seven real ES-DE sound names extract with their resolved paths`() {
        // The real seven, verbatim from Sound.cpp:213-219.
        val declared = ES_DE_NAVIGATION_SOUND_NAMES.map { soundElement(it, "/theme/sounds/$it.wav") }
        val paths = navigationSoundPaths(theme("system" to declared))

        assertEquals(ES_DE_NAVIGATION_SOUND_NAMES.toSet(), paths.keys)
        assertEquals("/theme/sounds/launch.wav", paths["launch"])
    }

    @Test
    fun `sounds duplicated across views by the all-view expansion extract once, not twice`() {
        // The parser expands `<view name="all">` into system+gamelist --
        // the same sound_<name> element appears in both.
        val element = soundElement("back", "/theme/back.wav")
        val paths = navigationSoundPaths(theme("system" to listOf(element), "gamelist" to listOf(element)))

        assertEquals(mapOf("back" to "/theme/back.wav"), paths)
    }

    @Test
    fun `a sound name real ES-DE never looks up is ignored`() {
        // Real ES-DE only ever reads the seven names (Sound.cpp:213-219);
        // a theme's own invented name has no consumer there either.
        val paths = navigationSoundPaths(
            theme("system" to listOf(soundElement("explosion", "/theme/boom.wav"))),
        )

        assertTrue(paths.isEmpty())
    }

    @Test
    fun `a sound element with no path property is skipped, and a null theme extracts nothing`() {
        val pathless = EsDeThemeElement(type = "sound", key = "sound_select", properties = emptyMap())

        assertTrue(navigationSoundPaths(theme("system" to listOf(pathless))).isEmpty())
        assertTrue(navigationSoundPaths(null).isEmpty())
    }

    @Test
    fun `non-sound elements never leak into the sound map`() {
        // An image element whose name happens to collide with a sound name.
        val image = EsDeThemeElement(
            type = "image",
            key = "image_launch",
            properties = mapOf("path" to EsDeThemeValue.Path("/theme/launch.png")),
        )

        assertTrue(navigationSoundPaths(theme("system" to listOf(image))).isEmpty())
    }

    @Test
    fun `animation extension dispatch matches real ES-DE plus the deliberate APNG extension`() {
        // Real ES-DE dispatch (SystemView.cpp:648-676): .gif and .json
        // only, everything else refused; droidtop adds .png/.apng.
        assertEquals(EsDeAnimationKind.GIF, esDeAnimationKind("/theme/anim.gif"))
        assertEquals(EsDeAnimationKind.GIF, esDeAnimationKind("/theme/ANIM.GIF"))
        assertEquals(EsDeAnimationKind.LOTTIE, esDeAnimationKind("/theme/anim.json"))
        assertEquals(EsDeAnimationKind.APNG, esDeAnimationKind("/theme/anim.png"))
        assertEquals(EsDeAnimationKind.APNG, esDeAnimationKind("/theme/anim.apng"))
        assertEquals(EsDeAnimationKind.UNSUPPORTED, esDeAnimationKind("/theme/anim.webm"))
        // The real "extension is missing" warning case (SystemView.cpp:667).
        assertEquals(EsDeAnimationKind.UNSUPPORTED, esDeAnimationKind("/theme/anim"))
    }
}
