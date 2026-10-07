package dev.droidtop.library.stores

import android.content.Context
import dev.droidtop.library.StoreDownloads
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The store registry, the install folder rule and the downloads map the stores share (docs/SPEC.md 7g, "Stores"). */
class StoreLibrariesTest {

    private class FakeStore(override val id: String, override val label: String) : StoreLibrary {
        override fun signedIn(context: Context) = false
        override fun signIn(context: Context): StoreSignIn = StoreSignIn.ApiKey("https://example.invalid/keys")
        override suspend fun completeSignIn(context: Context, secret: String) = Result.success<String?>(null)
        override suspend fun signOut(context: Context) = Result.success(Unit)
        override suspend fun sync(context: Context) = Result.success(0)
        override suspend fun games(context: Context) = emptyList<StoreGame>()
        override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress) = "Installed"
        override suspend fun uninstall(context: Context, gameId: String) = Result.success(Unit)
        override fun changeStamp(context: Context) = 1L
    }

    @Test
    fun `a library key finds the store that owns it, and only that one`() {
        val gog = FakeStore("gogtest", "GOG")
        StoreLibraries.register(gog)

        assertSame(gog, StoreLibraries.forKey("gogtest:1207658691"))
        assertNull(StoreLibraries.forKey("nosuchstore:1"))
        assertNull(StoreLibraries.forKey("no colon"))
        assertNull(StoreLibraries.forKey(null))
    }

    @Test
    fun `registering a store again replaces it rather than listing it twice`() {
        val first = FakeStore("dupe", "First")
        val second = FakeStore("dupe", "Second")
        StoreLibraries.register(first)
        StoreLibraries.register(second)

        assertEquals(1, StoreLibraries.all().count { it.id == "dupe" })
        assertSame(second, StoreLibraries.byId("dupe"))
    }

    @Test
    fun `a store row's key is the store and its own id`() {
        val game = StoreGame("gog", "1207658691", "Title", installed = false, installPath = null, sizeBytes = 0, artUrl = null)
        assertEquals("gog:1207658691", game.key)
    }

    @Test
    fun `each store installs into a folder of its own under the volume's Games folder`() {
        val root = StoreInstallJob.rootFor("/storage/emulated/0/Android/data/dev.droidtop.app/files", FakeStore("amazon", "Amazon"))
        assertEquals(File("/storage/emulated/0/Android/data/dev.droidtop.app/files/Games/Amazon"), root)
        val odd = StoreInstallJob.rootFor("/v", FakeStore("itch", "itch.io"))
        assertEquals(File("/v/Games/itch.io"), odd)
        val nothingLeft = StoreInstallJob.rootFor("/v", FakeStore("x", "???"))
        assertEquals(File("/v/Games/x"), nothingLeft)
    }

    @Test
    fun `two publishers share the downloads map and clearing one keeps the other`() {
        val steam = mapOf("steam:440" to StoreDownloads.Progress(0.5f, paused = false))
        val jobs = mapOf("gog:1" to StoreDownloads.Progress(0.1f, paused = true))
        StoreDownloads.publish("test_a", steam)
        StoreDownloads.publish("test_b", jobs)
        assertEquals(steam + jobs, StoreDownloads.active.value.filterKeys { it in setOf("steam:440", "gog:1") })

        StoreDownloads.publish("test_a", emptyMap())
        assertEquals(jobs, StoreDownloads.active.value.filterKeys { it in setOf("steam:440", "gog:1") })
        StoreDownloads.publish("test_b", emptyMap())
    }
}
