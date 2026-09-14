package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StoreOwnedPlatformTest {
    private fun system(id: String, ownedBy: String? = null) = ConsoleSystemDef(
        id = id,
        displayName = id,
        extensions = setOf("zip"),
        retroArchCore = null,
        ownedBy = ownedBy,
    )

    @Test
    fun `store-owned platform never resolves from its folder name`() {
        val steam = system("steam", ownedBy = "store")
        val epic = system("epic", ownedBy = "store")
        val systems = listOf(steam, epic).associateBy { it.id }

        assertNull(resolveSystem("Steam", systems))
        assertNull(resolveSystem("EPIC", systems))
    }

    @Test
    fun `ownership policy is data-driven rather than an id list`() {
        val futureStore = system("future-store", ownedBy = "STORE")
        val systems = mapOf(futureStore.id to futureStore)

        assertNull(resolveSystem("future-store", systems))
    }

    @Test
    fun `ordinary platform still resolves from its folder name`() {
        val nes = system("nes")

        assertSame(nes, resolveSystem("NES", mapOf(nes.id to nes)))
    }

    @Test
    fun `store records in the bundled database carry ownership metadata`() {
        val text = java.io.File("../vendor/droidtop-platforms/platforms-database.json").readText()
        val platforms = org.json.JSONObject(text).getJSONArray("platforms")
        val owners = (0 until platforms.length()).associate { index ->
            val row = platforms.getJSONObject(index)
            row.getString("id") to row.optString("ownedBy", "")
        }

        assertEquals("store", owners["steam"])
        assertEquals("store", owners["epic"])
    }
}
