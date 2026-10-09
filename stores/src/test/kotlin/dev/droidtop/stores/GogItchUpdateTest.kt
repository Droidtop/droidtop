package dev.droidtop.stores

import dev.droidtop.library.StoreUpdate
import dev.droidtop.stores.data.ItchUpload
import dev.droidtop.stores.gog.GOGStore
import dev.droidtop.stores.itch.ItchStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How GOG and itch.io say a newer build exists (docs/SPEC.md 7g, "Where an update comes from"; Droidtop/tracker#222). */
class GogItchUpdateTest {
    private fun upload(updatedAt: String) =
        ItchUpload(7L, "game.zip", null, 1L, true, false, false, false, updatedAt)

    @Test
    fun `GOG names a newer build when the build id changed`() {
        assertEquals(StoreUpdate.CURRENT, GOGStore.updateState("58123", "58123"))
        assertEquals(StoreUpdate.AVAILABLE, GOGStore.updateState("58123", "59001"))
    }

    @Test
    fun `itch names a newer build when the upload's stamp moved`() {
        assertEquals(StoreUpdate.CURRENT, ItchStore.updateState("2026-01-01T00:00:00Z", upload("2026-01-01T00:00:00Z")))
        assertEquals(StoreUpdate.AVAILABLE, ItchStore.updateState("2026-01-01T00:00:00Z", upload("2026-03-02T10:00:00Z")))
    }

    @Test
    fun `itch says nothing without a recorded stamp, a listed upload or a stamp to compare`() {
        assertNull(ItchStore.updateState("", upload("2026-03-02T10:00:00Z")))
        assertNull(ItchStore.updateState("2026-01-01T00:00:00Z", null))
        assertNull(ItchStore.updateState("2026-01-01T00:00:00Z", upload("")))
    }
}
