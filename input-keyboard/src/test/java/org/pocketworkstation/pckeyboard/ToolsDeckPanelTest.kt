package org.pocketworkstation.pckeyboard

import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/** A tool panel takes the keys' place and no more (Droidtop/tracker#903: the emoji panel covered the whole screen). */
class ToolsDeckPanelTest {
    @Test fun aPanelIsTheKeysHeight() {
        assertEquals(486, ToolsDeck.panelHeight(486))
    }

    @Test fun beforeTheKeysAreLaidOutItMatchesItsParent() {
        assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, ToolsDeck.panelHeight(0))
    }
}
