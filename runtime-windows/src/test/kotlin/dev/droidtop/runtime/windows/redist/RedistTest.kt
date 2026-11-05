package dev.droidtop.runtime.windows.redist

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RedistTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun node(key: String, value: ByteArray, text: Boolean, children: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun pad() { while (out.size() % 4 != 0) out.write(0) }
        u16(0); u16(if (text) value.size / 2 else value.size); u16(if (text) 1 else 0)
        key.forEach { u16(it.code) }; u16(0); pad()
        out.write(value); pad()
        children.forEach { out.write(it); pad() }
        val b = out.toByteArray()
        b[0] = (b.size and 0xFF).toByte(); b[1] = (b.size shr 8).toByte()
        return b
    }

    private fun s(v: String) = (v + "\u0000").toByteArray(Charsets.UTF_16LE)

    private val vcredist = PeInfo(
        false,
        mapOf(
            "CompanyName" to "Microsoft Corporation",
            "ProductName" to "Microsoft Visual C++ 2015-2022 Redistributable (x64) - 14.40.33810",
            "OriginalFilename" to "VC_redist.x64.exe",
        ),
        "Microsoft Corporation",
    )

    private val vdf = """
        "InstallScript"
        {
            "Run Process"
            {
                "VCRedist2022"
                {
                    "HasRunKey"   "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2022"
                    "process 1"   "%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe"
                    "command 1"   "/q /norestart"
                    "process 2"   "%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x86.exe"
                }
            }
            // a comment
        }
    """.trimIndent()

    @Test
    fun `the version block's strings are read, the VarFileInfo skipped`() {
        val strings = listOf("CompanyName" to "Microsoft Corporation", "OriginalFilename" to "VC_redist.x64.exe")
            .map { (k, v) -> node(k, s(v), true, emptyList()) }
        val sfi = node("StringFileInfo", ByteArray(0), true, listOf(node("040904b0", ByteArray(0), true, strings)))
        val vfi = node("VarFileInfo", ByteArray(0), true, listOf(node("Translation", byteArrayOf(9, 4, -80, 4), false, emptyList())))
        val parsed = PeInfo.versionStrings(node("VS_VERSION_INFO", ByteArray(52), false, listOf(vfi, sfi)))
        assertEquals("Microsoft Corporation", parsed["CompanyName"])
        assertEquals("VC_redist.x64.exe", parsed["OriginalFilename"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun `a redistributable is matched by version info and signer, never by one alone`() {
        assertEquals("vcredist-2015-2022", RedistTable.match(vcredist)?.id)
        assertNull(RedistTable.match(vcredist.copy(signer = "Someone Else Ltd")))
        assertNull(RedistTable.match(vcredist.copy(signer = null)))
        assertNull(RedistTable.match(vcredist.copy(strings = mapOf("OriginalFilename" to "VC_redist.x64.exe"))))
    }

    @Test
    fun `what droidtop provides is recognised as provided`() {
        val dotnet = PeInfo(false, mapOf("CompanyName" to "Microsoft Corporation", "ProductName" to "Microsoft .NET Framework 4.8"), "Microsoft Corporation")
        assertTrue(RedistTable.match(dotnet)!!.provided)
    }

    @Test
    fun `the signer is the certificate subject's organisation`() {
        assertEquals("Microsoft Corporation", PeInfo.organisation("CN=Microsoft Corporation,O=Microsoft Corporation,L=Redmond,ST=Washington,C=US"))
        assertEquals("NVIDIA Corporation", PeInfo.organisation("CN=NVIDIA Corporation,O=\"NVIDIA Corporation\",C=US"))
    }

    @Test
    fun `installscript's Run Process paths are read`() {
        assertEquals(
            listOf("%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe", "%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x86.exe"),
            RedistFinder.runProcessPaths(vdf),
        )
    }

    @Test
    fun `the finder takes the stores' and engines' folders and nothing else`() {
        val game = tmp.newFolder("game")
        File(game, "_CommonRedist/vcredist/2022").mkdirs()
        File(game, "_CommonRedist/vcredist/2022/VC_redist.x64.exe").writeText("x")
        File(game, "Engine/Extras/Redist/en-us").mkdirs()
        File(game, "Engine/Extras/Redist/en-us/vc_redist.x64.exe").writeText("x")
        File(game, "installscript.vdf").writeText(vdf)
        File(game, "Game.exe").writeText("x")
        val found = RedistFinder.find(game).map { it.relativeTo(game).path }
        assertTrue("_CommonRedist/vcredist/2022/VC_redist.x64.exe" in found)
        assertTrue("Engine/Extras/Redist/en-us/vc_redist.x64.exe" in found)
        assertTrue("Game.exe" !in found)
        assertNull(RedistFinder.resolve(game, "%INSTALLDIR%\\..\\etc\\passwd"))
        assertNull(PeInfo.read(File(game, "Game.exe")))
    }
}
