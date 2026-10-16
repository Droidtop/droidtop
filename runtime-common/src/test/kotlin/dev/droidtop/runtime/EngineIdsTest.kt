package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/SPEC.md 3d, "Container ids": the id both tools see, and every way a container is named. */
class EngineIdsTest {
    private fun info(id: String, name: String) = ContainerInfo(Container(id, ContainerRole.SIBLING, ContainerBackend.PROOT, "/r/$id"), running = false, name = name)

    @Test
    fun `containers whose own ids share their start get engine ids that do not`() {
        val a = EngineIds.of("droidtop-sibling-1a2b3c4d")
        val b = EngineIds.of("droidtop-sibling-99887766")
        assertEquals(64, a.length)
        assertEquals(a, EngineIds.of("droidtop-sibling-1a2b3c4d"))
        // Both tools' ps show twelve characters: droidtop's ids all read droidtop-sib there.
        assertNotEquals(a.take(12), b.take(12))
    }

    @Test
    fun `a container is found by its name, either id, or a unique start of either`() {
        val web = info("droidtop-sibling-1a2b3c4d", "web")
        val job = info("droidtop-sibling-99887766", "job")
        val all = listOf(web, job)
        assertEquals(web, EngineIds.find(all, "web"))
        assertEquals(web, EngineIds.find(all, "/web"))
        assertEquals(job, EngineIds.find(all, "droidtop-sibling-99887766"))
        assertEquals(job, EngineIds.find(all, EngineIds.of(job.container.id)))
        assertEquals(job, EngineIds.find(all, EngineIds.of(job.container.id).take(12)))
        // droidtop-sib starts both of droidtop's ids, so it names neither.
        assertNull(EngineIds.find(all, "droidtop-sib"))
        assertNull(EngineIds.find(all, "ab"))
    }

    @Test
    fun `an image's creation time is its config's created`() {
        assertEquals(1_700_000_000_123L, OciImageStore.createdMs("""{"created":"2023-11-14T22:13:20.123456789Z","config":{}}"""))
        assertNull(OciImageStore.createdMs("""{"config":{}}"""))
        assertNull(OciImageStore.createdMs("""{"created":"1970-01-01T00:00:00Z"}"""))
        assertNull(OciImageStore.createdMs("not json"))
    }
}
