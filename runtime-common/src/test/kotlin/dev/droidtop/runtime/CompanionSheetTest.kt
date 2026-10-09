package dev.droidtop.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-screen companion sheet (CompanionSheet.kt, Droidtop/tracker#414 slice C13). */
class CompanionSheetTest {
    @Test fun `the sheet is offered only with no second screen for the companion`() {
        assertTrue(CompanionSheet.offered(1))
        assertTrue(CompanionSheet.offered(0))
        assertFalse(CompanionSheet.offered(2))
        assertFalse(CompanionSheet.offered(3))
    }
}
