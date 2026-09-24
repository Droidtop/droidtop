package dev.droidtop.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class OciFlattenerTest {
    /** One layer, as an uncompressed tar in memory. */
    class Layer {
        private val bytes = ByteArrayOutputStream()
        private val tar = TarArchiveOutputStream(bytes).apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        }

        fun dir(name: String, mode: Int = 0b111_101_101) = apply {
            put(TarArchiveEntry(name.trimEnd('/') + "/").also { it.mode = mode })
        }

        fun file(name: String, content: String, mode: Int = 0b110_100_100, uid: Long = 0, user: String = "root") = apply {
            val data = content.toByteArray()
            tar.putArchiveEntry(TarArchiveEntry(name).also {
                it.size = data.size.toLong(); it.mode = mode; it.setUserId(uid); it.userName = user
            })
            tar.write(data)
            tar.closeArchiveEntry()
        }

        fun symlink(name: String, target: String) = apply {
            put(TarArchiveEntry(name, TarConstants.LF_SYMLINK).also { it.linkName = target })
        }

        fun hardlink(name: String, target: String) = apply {
            put(TarArchiveEntry(name, TarConstants.LF_LINK).also { it.linkName = target })
        }

        fun whiteout(name: String) = file(name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + ".wh." + name.substringAfterLast('/'), "")

        fun opaque(dir: String) = file("${dir.trimEnd('/')}/.wh..wh..opq", "")

        private fun put(entry: TarArchiveEntry) {
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
        }

        fun bytes(): ByteArray {
            tar.finish()
            return bytes.toByteArray()
        }
    }

    data class Emitted(val name: String, val type: Byte, val link: String, val content: String, val mode: Int, val uid: Long, val user: String)

    private class Collect : RootfsEntrySink {
        val entries = mutableListOf<Emitted>()
        override fun accept(entry: TarArchiveEntry, data: InputStream) {
            val type = when {
                entry.isDirectory -> TarConstants.LF_DIR
                entry.isSymbolicLink -> TarConstants.LF_SYMLINK
                entry.isLink -> TarConstants.LF_LINK
                entry.isFIFO -> TarConstants.LF_FIFO
                else -> TarConstants.LF_NORMAL
            }
            val content = if (type == TarConstants.LF_NORMAL) data.readBytes().decodeToString() else ""
            entries += Emitted(entry.name, type, entry.linkName, content, entry.mode, entry.longUserId, entry.userName)
        }

        fun named(name: String) = entries.singleOrNull { it.name == name || it.name == "$name/" }
    }

    /** Flattens [layers], given bottom (oldest) first. */
    private fun flatten(vararg layers: Layer): Pair<Collect, OciFlattener.Result> {
        val sink = Collect()
        val tars = layers.map { it.bytes() }
        val result = OciFlattener.flatten(tars.map { bytes -> { OciFlattener.LayerStream.plain(ByteArrayInputStream(bytes)) } }, sink)
        return sink to result
    }

    @Test
    fun `a higher layer's file wins and each path is emitted once`() {
        val (out, _) = flatten(
            Layer().dir("etc").file("etc/os-release", "old").file("etc/hostname", "base"),
            Layer().file("etc/os-release", "new"),
        )
        assertEquals("new", out.named("etc/os-release")!!.content)
        assertEquals("base", out.named("etc/hostname")!!.content)
        assertEquals(out.entries.size, out.entries.map { it.name }.toSet().size)
    }

    @Test
    fun `whiteouts hide lower files and directories, not their own layer's`() {
        val (out, _) = flatten(
            Layer().dir("var").dir("var/cache").file("var/cache/a", "a").file("gone", "x"),
            Layer().whiteout("var/cache").whiteout("gone").file("gone", "readded in the same layer"),
        )
        assertNull(out.named("var/cache"))
        assertNull(out.named("var/cache/a"))
        assertEquals("readded in the same layer", out.named("gone")!!.content)
        assertTrue(out.entries.none { it.name.contains(".wh.") })
    }

    @Test
    fun `an opaque directory hides what lower layers put in it but keeps its own`() {
        val (out, _) = flatten(
            Layer().dir("opt").file("opt/old", "old"),
            Layer().dir("opt").opaque("opt").file("opt/new", "new"),
        )
        assertNull(out.named("opt/old"))
        assertEquals("new", out.named("opt/new")!!.content)
        assertTrue(out.named("opt")!!.type == TarConstants.LF_DIR)
    }

    @Test
    fun `names that climb out are dropped and reported, absolute ones made relative`() {
        val (out, result) = flatten(Layer().file("../../escape", "x").file("a/../../b", "x").file("/etc/abs", "ok"))
        assertEquals(listOf("etc/abs"), out.entries.filter { it.type == TarConstants.LF_NORMAL }.map { it.name })
        assertEquals(2, result.skippedCount)
    }

    @Test
    fun `nothing is emitted beneath a symlink from the same layer`() {
        val (out, result) = flatten(Layer().symlink("evil", "/data/data").file("evil/owned", "x").symlink("up", "../../..").file("up/x", "x"))
        assertEquals(setOf("evil", "up"), out.entries.map { it.name }.toSet())
        assertEquals(2, result.skippedCount)
    }

    @Test
    fun `a symlink a higher layer put there shadows a lower directory silently`() {
        val (out, result) = flatten(
            Layer().dir("lib").file("lib/libc.so", "x"),
            Layer().symlink("lib", "usr/lib"),
        )
        assertEquals(listOf("lib"), out.entries.map { it.name })
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun `a path with anything emitted beneath it stays a directory, so a lower symlink of that name is dropped`() {
        val (out, _) = flatten(
            Layer().symlink("etc", "/system/etc"),
            Layer().file("etc/passwd", "root:x:0:0"),
        )
        assertEquals(listOf("etc/passwd"), out.entries.map { it.name })
    }

    @Test
    fun `hard links come last, only to their own layer's surviving files`() {
        val (out, result) = flatten(
            Layer().file("usr/bin/lower", "lower"),
            Layer().hardlink("usr/bin/perl5", "usr/bin/perl").file("usr/bin/perl", "perl")
                .hardlink("usr/bin/steal", "../../../data/system/packages.xml")
                .hardlink("usr/bin/other-layer", "usr/bin/lower"),
        )
        assertEquals(TarConstants.LF_LINK, out.entries.last().type)
        assertEquals("usr/bin/perl", out.named("usr/bin/perl5")!!.link)
        assertNull(out.named("usr/bin/steal"))
        assertNull(out.named("usr/bin/other-layer"))
        assertEquals(2, result.skippedCount)
    }

    @Test
    fun `headers keep numeric ids and permission bits, not names`() {
        val (out, _) = flatten(Layer().file("usr/bin/sudo", "x", mode = 0b100_111_101_101, uid = 0, user = "nobody").file("home/u/f", "x", uid = 1000, user = "shell"))
        val sudo = out.named("usr/bin/sudo")!!
        assertEquals(0b100_111_101_101, sudo.mode)
        assertEquals("", sudo.user)
        assertEquals(1000L, out.named("home/u/f")!!.uid)
    }

    @Test
    fun `the tar stream sink writes what it is given, long names included, without pax headers`() {
        val longName = "usr/share/" + "d".repeat(120) + "/file"
        val bytes = ByteArrayOutputStream()
        val sink = TarStreamSink(bytes)
        OciFlattener.flatten(
            listOf({ OciFlattener.LayerStream.plain(ByteArrayInputStream(Layer().file(longName, "long").symlink("s", "/" + "t".repeat(150)).bytes())) }),
            sink,
        )
        sink.close()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("PaxHeaders"))

        val read = TarArchiveInputStream(ByteArrayInputStream(bytes.toByteArray()))
        val names = mutableMapOf<String, String>()
        while (true) {
            val entry = read.nextEntry ?: break
            names[entry.name] = if (entry.isSymbolicLink) entry.linkName else read.readBytes().decodeToString()
        }
        assertEquals("long", names[longName])
        assertEquals("/" + "t".repeat(150), names["s"])
    }
}
