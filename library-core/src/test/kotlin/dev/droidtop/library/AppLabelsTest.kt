package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Apps tab's label fallback (see [AppLabels]). */
class AppLabelsTest {

    @Test
    fun `a real label is the label`() {
        assertEquals("BlueStacks X", AppLabels.labelFor("BlueStacks X", "com.bluestacks.bsxlauncher"))
    }

    @Test
    fun `a class name is not a label`() {
        // The rig's own row, verbatim.
        assertEquals(
            "Bsxlauncher",
            AppLabels.labelFor("com.bluestacks.bsxlauncher.Main", "com.bluestacks.bsxlauncher"),
        )
    }

    @Test
    fun `no label at all falls back the same way`() {
        assertEquals("Vlc", AppLabels.labelFor(null, "org.videolan.vlc"))
        assertEquals("Vlc", AppLabels.labelFor("  ", "org.videolan.vlc"))
    }

    @Test
    fun `a platform segment is not what the app is called`() {
        assertEquals("Cool Game", AppLabels.humanisePackage("com.my_studio.coolGame.android"))
        assertEquals("Studio", AppLabels.humanisePackage("com.studio.app"))
    }

    @Test
    fun `a package of nothing but generic words still gets a name`() {
        assertEquals("App", AppLabels.humanisePackage("com.android.app"))
    }

    @Test
    fun `a title with a dot in it is a title`() {
        assertEquals("Mr. Driller", AppLabels.labelFor("Mr. Driller", "com.namco.drill"))
        assertEquals("S.T.A.L.K.E.R.", AppLabels.labelFor("S.T.A.L.K.E.R.", "com.gsc.stalker"))
    }

    @Test
    fun `nothing readable leaves the package itself`() {
        assertEquals("...", AppLabels.labelFor(null, "..."))
    }
}
