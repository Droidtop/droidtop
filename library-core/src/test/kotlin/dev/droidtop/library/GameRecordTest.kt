package dev.droidtop.library

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecordTest {
    private fun dir(): File = Files.createTempDirectory("droidtop-records").toFile()

    private fun engineRecord(id: String = "renpy:one") = GameRecord(
        entry = LibraryEntry(id = id, title = "One", kind = LibraryEntryKind.RENPY),
        provider = "EngineGameProvider",
        root = "/games",
        part = "/games/one",
        launch = LaunchFacts.Engine(
            gameRoot = "/games/one",
            engine = "RENPY",
            engineVersion = "8.2.3",
            enginehostTarget = EnginehostTarget(engine = "renpy", engineContext = null),
            runtimeRequirements = mapOf("python" to "3.11"),
        ),
    )

    @Test
    fun `a written record reads back the same launch facts`() {
        val store = FileGameRecordStore(dir())
        val record = engineRecord()
        store.put(record)
        val read = store.get(record.entry.id)
        assertEquals(record, read)
    }

    @Test
    fun `a rom record round-trips its own launch facts`() {
        val store = FileGameRecordStore(dir())
        val record = GameRecord(
            entry = LibraryEntry(id = "/roms/nes/g.nes", title = "G", kind = LibraryEntryKind.CONSOLE_ROM, systemId = "nes"),
            provider = "ConsoleRomProvider",
            root = "/roms",
            part = "nes",
            launch = LaunchFacts.Rom(file = "/roms/nes/g.nes", systemId = "nes", altEmulator = "retroarch"),
        )
        store.put(record)
        assertEquals(record, store.get(record.entry.id))
    }

    @Test
    fun `an id never asked for reads null`() {
        val store = FileGameRecordStore(dir())
        assertNull(store.get("nothing:here"))
    }

    @Test
    fun `writing the same record again does not touch the file`() {
        val d = dir()
        val store = FileGameRecordStore(d)
        val record = engineRecord()
        store.put(record)
        val file = d.walkTopDown().first { it.isFile }
        val before = file.lastModified()
        Thread.sleep(5)
        store.put(record)
        assertEquals(before, file.lastModified())
    }

    @Test
    fun `writing a changed record replaces it`() {
        val store = FileGameRecordStore(dir())
        val record = engineRecord()
        store.put(record)
        val changed = record.copy(entry = record.entry.copy(title = "Two"))
        store.put(changed)
        assertEquals("Two", store.get(record.entry.id)?.entry?.title)
    }

    @Test
    fun `a corrupt record file reads as null, never throws`() {
        val d = dir()
        val store = FileGameRecordStore(d)
        val id = "broken:one"
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val shard = File(d, hash.substring(0, 2)).apply { mkdirs() }
        File(shard, "$hash.json").writeText("not json at all")
        assertNull(store.get(id))
    }

    @Test
    fun `a record from a future format is treated as no record`() {
        val d = dir()
        val store = FileGameRecordStore(d)
        val record = engineRecord()
        val future = Json { encodeDefaults = true; classDiscriminator = "kind" }
            .encodeToString(GameRecord.serializer(), record.copy(formatVersion = GameRecord.FORMAT_VERSION + 1))
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(record.entry.id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val shard = File(d, hash.substring(0, 2)).apply { mkdirs() }
        File(shard, "$hash.json").writeText(future)
        assertNull(store.get(record.entry.id))
    }

    @Test
    fun `delete removes the file`() {
        val store = FileGameRecordStore(dir())
        val record = engineRecord()
        store.put(record)
        assertTrue(store.get(record.entry.id) != null)
        store.delete(record.entry.id)
        assertNull(store.get(record.entry.id))
    }

    @Test
    fun `NoOpGameRecordStore never remembers anything`() {
        val store = NoOpGameRecordStore
        store.put(engineRecord())
        assertNull(store.get("renpy:one"))
    }

    @Test
    fun `ids shard into two-character directories and never leak the raw id`() {
        val d = dir()
        val store = FileGameRecordStore(d)
        val record = engineRecord(id = "/games/some game with spaces & \$ymbols.exe")
        store.put(record)
        val written = d.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, written.size)
        assertFalse(written.single().name.contains("some game"))
        assertEquals(2, written.single().parentFile.name.length)
    }
}
