package dev.droidtop.library.stores

import android.content.Context
import dev.droidtop.library.ScanPrune
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The store registry, the install folder rule and the downloads map the stores share (docs/SPEC.md 7g, "Stores"). */
class StoreLibrariesTest {

    private class FakeStore(override val id: String, override val label: String) : StoreLibrary {
        override fun signedIn(context: Context) = false
        override val signInKind = StoreSignInKind.API_KEY
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
    fun `each store installs into a folder of its own inside the game folder`() {
        val root = StoreInstallJob.rootFor("/storage/emulated/0/Games", FakeStore("amazon", "Amazon"))
        assertEquals(File("/storage/emulated/0/Games/Amazon"), root)
        val odd = StoreInstallJob.rootFor("/v", FakeStore("itch", "itch.io"))
        assertEquals(File("/v/itch.io"), odd)
        val nothingLeft = StoreInstallJob.rootFor("/v", FakeStore("x", "???"))
        assertEquals(File("/v/x"), nothingLeft)
    }

    @Test
    fun `a library walk passes over a store game still downloading, and only that`() {
        StoreLibraries.register(FakeStore("prunetest", "Prune Store"))
        val games = java.nio.file.Files.createTempDirectory("games").toFile()
        val storeFolder = File(games, "Prune Store")
        val downloading = File(storeFolder, "Half Game").apply { mkdirs() }
        File(downloading, StoreInstallJob.IN_PROGRESS_MARKER).writeText("")
        val finished = File(storeFolder, "Whole Game").apply { mkdirs() }
        // The same marker outside a store's folder proves nothing.
        val elsewhere = File(games, "Their Game").apply { mkdirs() }
        File(elsewhere, StoreInstallJob.IN_PROGRESS_MARKER).writeText("")

        assertEquals("a store is still downloading it", ScanPrune.skipReason(downloading))
        assertNull(ScanPrune.skipReason(finished))
        assertNull(ScanPrune.skipReason(elsewhere))
        games.deleteRecursively()
    }

    @Test
    fun `each game shows its newest store job while it runs, waits or failed, and nothing once it finished well`() {
        fun job(id: String, key: String, at: Long, done: Boolean = false, ok: Boolean = true) = PluginJobsCenter.Entry(
            jobId = id, pluginId = "droidtop", pluginLabel = "GOG", capability = null, title = "t", startedAtMs = at,
            nativeKind = StoreInstallJob.KIND, resumePayload = key, done = done,
            result = if (!done) null else if (ok) PluginResult.success() else PluginResult.failure("failed"),
        )
        val keys = mapOf("a" to "gog:1", "b" to "gog:1", "c" to "gog:2", "d" to "gog:3", "e" to "gog:3")
        val live = StoreInstallJob.byGame(
            listOf(
                job("a", "gog:1", at = 1, done = true, ok = false),
                job("b", "gog:1", at = 2),
                job("c", "gog:2", at = 1, done = true, ok = false),
                job("d", "gog:3", at = 1, done = true, ok = false),
                job("e", "gog:3", at = 2, done = true),
            ),
        ) { keys[it.jobId] }
        assertEquals("b", live["gog:1"]?.jobId)
        assertEquals("c", live["gog:2"]?.jobId)
        assertNull("a later install that finished well hides the old failure", live["gog:3"])
    }
}
