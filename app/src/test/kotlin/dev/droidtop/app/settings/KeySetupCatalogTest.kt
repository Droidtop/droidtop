package dev.droidtop.app.settings

import dev.droidtop.library.scraper.ScraperKeyService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A key's how-to is one hint under its row, never a numbered list (Droidtop/tracker#869). */
class KeySetupCatalogTest {
    @Test fun `the steps between opening the page and pasting are one hint`() {
        assertEquals("Sign in to your account, open Settings, copy the API key", KeySetupCatalog.stepsHint(ScraperKeyService.TORBOX))
    }

    @Test fun `every service's hint is one line without a number or the paste step`() {
        ScraperKeyService.entries.forEach { service ->
            val hint = KeySetupCatalog.stepsHint(service)
            assertFalse(service.name, hint.contains('\n') || hint.endsWith(" here") || hint.isBlank())
        }
    }
}
