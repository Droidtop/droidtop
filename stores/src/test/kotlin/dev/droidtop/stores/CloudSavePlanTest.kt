package dev.droidtop.stores

import dev.droidtop.library.stores.SaveChoice
import dev.droidtop.stores.util.CloudSavePlan
import dev.droidtop.stores.util.CloudSavePlan.Base
import dev.droidtop.stores.util.CloudSavePlan.Decision
import dev.droidtop.stores.util.CloudSavePlan.Facts
import dev.droidtop.stores.util.CloudSaveState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The one decision every store's cloud save sync makes (docs/SPEC.md 7g, "Stores", Droidtop/tracker#224). */
class CloudSavePlanTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun facts(sha: String, time: Long = 1_000L) = Facts(sha, 10L, time)

    private fun decide(base: Map<String, String>, local: Map<String, Facts>, remote: Map<String, Facts>) =
        CloudSavePlan.decide(CloudSavePlan.same(base), local, remote)

    @Test
    fun `equal files on both sides need nothing`() {
        val both = mapOf("a" to facts("1"))
        assertEquals(Decision.UpToDate, decide(emptyMap(), both, both))
        assertEquals(Decision.UpToDate, decide(mapOf("a" to "0"), both, both))
        assertEquals(Decision.UpToDate, decide(emptyMap(), emptyMap(), emptyMap()))
    }

    @Test
    fun `only this device changed, so the files go up and a deleted one is deleted there`() {
        val base = mapOf("a" to "1", "b" to "2", "c" to "3")
        val local = mapOf("a" to facts("1"), "b" to facts("22"), "d" to facts("4"))
        val remote = mapOf("a" to facts("1"), "b" to facts("2"), "c" to facts("3"))
        val decision = decide(base, local, remote) as Decision.Upload
        assertEquals(setOf("b", "d"), decision.send.toSet())
        assertEquals(listOf("c"), decision.removeRemote)
    }

    @Test
    fun `only the cloud changed, so the files come down and a file it dropped goes here`() {
        val base = mapOf("a" to "1", "b" to "2")
        val local = mapOf("a" to facts("1"), "b" to facts("2"))
        val remote = mapOf("a" to facts("11"), "c" to facts("3"))
        val decision = decide(base, local, remote) as Decision.Download
        assertEquals(setOf("a", "c"), decision.fetch.toSet())
        assertEquals(listOf("b"), decision.removeLocal)
    }

    @Test
    fun `a first sync with files on one side only takes them across`() {
        val files = mapOf("a" to facts("1"))
        assertTrue(decide(emptyMap(), files, emptyMap()) is Decision.Upload)
        assertTrue(decide(emptyMap(), emptyMap(), files) is Decision.Download)
    }

    @Test
    fun `a first sync with different files on both sides is a conflict`() {
        val local = mapOf("a" to facts("1", time = 5_000L))
        val remote = mapOf("a" to facts("2", time = 9_000L), "b" to facts("3", time = 2_000L))
        val conflict = decide(emptyMap(), local, remote) as Decision.Conflict
        assertEquals(5_000L, conflict.local.timeMs)
        assertEquals(9_000L, conflict.cloud.timeMs)
        assertEquals(1, conflict.local.files)
        assertEquals(2, conflict.cloud.files)
    }

    @Test
    fun `both sides changed since the last sync is a conflict, and choosing a side makes it win`() {
        val base = mapOf("a" to "1", "gone" to "9")
        val local = mapOf("a" to facts("2"), "mine" to facts("7"))
        val remote = mapOf("a" to facts("3"), "theirs" to facts("8"))
        assertTrue(decide(base, local, remote) is Decision.Conflict)
        val keepLocal = CloudSavePlan.settle(SaveChoice.LOCAL, local, remote) as Decision.Upload
        assertEquals(setOf("a", "mine"), keepLocal.send.toSet())
        assertEquals(listOf("theirs"), keepLocal.removeRemote)
        val keepCloud = CloudSavePlan.settle(SaveChoice.CLOUD, local, remote) as Decision.Download
        assertEquals(setOf("a", "theirs"), keepCloud.fetch.toSet())
        assertEquals(listOf("mine"), keepCloud.removeLocal)
    }

    @Test
    fun `choosing a side when the files already match changes nothing`() {
        val both = mapOf("a" to facts("1"))
        assertEquals(Decision.UpToDate, CloudSavePlan.settle(SaveChoice.LOCAL, both, both))
        assertEquals(Decision.UpToDate, CloudSavePlan.settle(SaveChoice.CLOUD, both, both))
    }

    @Test
    fun `a file both sides lost says nothing and does not make a false conflict later`() {
        val base = mapOf("gone" to "1", "a" to "5")
        val local = mapOf("a" to facts("5"))
        val remote = mapOf("a" to facts("6"))
        assertTrue(decide(base, local, remote) is Decision.Download)
    }

    @Test
    fun `a cloud that names files by another hash is unchanged while its own fingerprint is the one kept`() {
        // GOG: this device knows a file by one hash, the cloud by the MD5 of what it stores.
        val base = mapOf("slot1" to Base(local = "aaa", remote = "md5-1"))
        val local = mapOf("slot1" to facts("aaa"))
        assertEquals(Decision.UpToDate, CloudSavePlan.decide(base, local, mapOf("slot1" to facts("md5-1"))))
        val changedThere = CloudSavePlan.decide(base, local, mapOf("slot1" to facts("md5-2"))) as Decision.Download
        assertEquals(listOf("slot1"), changedThere.fetch)
        val changedHere = CloudSavePlan.decide(base, mapOf("slot1" to facts("bbb")), mapOf("slot1" to facts("md5-1"))) as Decision.Upload
        assertEquals(listOf("slot1"), changedHere.send)
        assertTrue(CloudSavePlan.decide(base, mapOf("slot1" to facts("bbb")), mapOf("slot1" to facts("md5-2"))) is Decision.Conflict)
    }

    @Test
    fun `the last-synced state round trips, keeps the cloud's own fingerprints, and a damaged one is empty`() {
        val state = CloudSaveState(
            mapOf("%gameinstall%save.dat" to Facts("abc", 12L, 99L), "slot" to Facts("def", 1L, 2L)),
            remote = mapOf("slot" to "md5"),
        )
        assertEquals(state, CloudSaveState.decode(CloudSaveState.encode(state)))
        assertEquals(CloudSaveState(), CloudSaveState.decode("nonsense"))
        assertEquals(mapOf("%gameinstall%save.dat" to "abc", "slot" to "def"), state.shas)
        assertEquals(Base("abc", "abc"), state.base["%gameinstall%save.dat"])
        assertEquals(Base("def", "md5"), state.base["slot"])
        val dir = temp.newFolder()
        val file = CloudSaveState.file(dir, "steam", "7")
        assertEquals(File(dir, "steam/cloud/7.json"), file)
        CloudSaveState.save(file, state)
        assertEquals(state, CloudSaveState.load(file))
        assertEquals(CloudSaveState(), CloudSaveState.load(CloudSaveState.file(dir, "steam", "8")))
        // An id is never a path.
        assertEquals(File(dir, "epic/cloud/.._x.json"), CloudSaveState.file(dir, "epic", "../x"))
    }
}
