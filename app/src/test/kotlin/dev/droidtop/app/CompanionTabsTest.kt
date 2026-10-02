package dev.droidtop.app

import dev.droidtop.display.SecondaryDisplayContent.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTabsTest {
    @Test fun everyModeGetsTheFourCommonTabsInOrder() {
        val tabs = companionTabs(Mode.GAMING, SecondScreenInputPrefs.Role.COMPANION)
        assertEquals(
            listOf(CompanionTab.HOME, CompanionTab.TASKS, CompanionTab.PERFORMANCE, CompanionTab.SYSTEM),
            tabs,
        )
    }

    @Test fun desktopAddsTheInputSurfaceAndOpensOnIt() {
        val role = SecondScreenInputPrefs.defaultFor(Mode.DESKTOP)
        assertTrue(companionTabs(Mode.DESKTOP, role).contains(CompanionTab.INPUT))
        assertEquals(CompanionTab.INPUT, defaultCompanionTab(role))
    }

    @Test fun desktopWithTheCompanionRoleStillOffersInputButOpensOnHome() {
        val tabs = companionTabs(Mode.DESKTOP, SecondScreenInputPrefs.Role.COMPANION)
        assertTrue(tabs.contains(CompanionTab.INPUT))
        assertEquals(CompanionTab.HOME, defaultCompanionTab(SecondScreenInputPrefs.Role.COMPANION))
    }

    @Test fun anotherModeSetToTheInputRoleGetsTheTabToo() {
        assertTrue(companionTabs(Mode.GAMING, SecondScreenInputPrefs.Role.INPUT).contains(CompanionTab.INPUT))
        assertFalse(companionTabs(Mode.GAMING, SecondScreenInputPrefs.Role.COMPANION).contains(CompanionTab.INPUT))
    }

    @Test fun storageTextReadsInGigabytes() {
        assertEquals("12.3 GB free of 128.0 GB", storageText(12_300_000_000L, 128_000_000_000L))
    }
}
