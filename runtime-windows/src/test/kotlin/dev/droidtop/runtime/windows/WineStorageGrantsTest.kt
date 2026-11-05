package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Test

class WineStorageGrantsTest {
    private val volumes = listOf("/storage/emulated/0", "/storage/1234-ABCD")
    private val private = "/data/user/0/dev.droidtop"
    private val own = listOf(
        "D" to "/storage/emulated/0/Download",
        "E" to "/data/user/0/dev.droidtop/storage",
        "F" to "/storage/emulated/0",
    )

    @Test
    fun `without grants a whole-volume drive is not linked, ordinary ones are`() {
        assertEquals(own.take(2), WineStorageGrants.drives(own, volumes, private, deviceSystem = false, allStorage = false))
    }

    @Test
    fun `all internal storage keeps the volume drive and adds every other volume under a free letter`() {
        assertEquals(
            own + ("G" to "/storage/1234-ABCD"),
            WineStorageGrants.drives(own, volumes, private, deviceSystem = false, allStorage = true),
        )
    }

    @Test
    fun `a drive on droidtop's private folder itself needs device system folders`() {
        val withPrivate = own.take(2) + ("H" to private)
        assertEquals(own.take(2), WineStorageGrants.drives(withPrivate, volumes, private, deviceSystem = false, allStorage = false))
        assertEquals(withPrivate, WineStorageGrants.drives(withPrivate, volumes, private, deviceSystem = true, allStorage = false))
    }

    @Test
    fun `the game's own folder gets a drive when the whole-volume drive that held it is not linked`() {
        val game = "/storage/emulated/0/Games/Reus"
        assertEquals(
            own.take(2) + ("F" to game),
            WineStorageGrants.drives(own, volumes, private, deviceSystem = false, allStorage = false, required = listOf(game)),
        )
        // Under the grant the volume drive holds it, and nothing is added.
        assertEquals(
            own + ("G" to "/storage/1234-ABCD"),
            WineStorageGrants.drives(own, volumes, private, deviceSystem = false, allStorage = true, required = listOf(game)),
        )
    }
}
