package dev.droidtop.runtime.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskyActionsTest {
    private class RecordingBackend : ElevatedBackend {
        val calls = mutableListOf<String>()

        override fun state() = BackendState.READY

        override fun forceStop(packageName: String): ForceStopResult = ForceStopResult.NoProvider

        override fun exec(argv: List<String>): ShellOutput? = null

        override fun grantPermission(packageName: String, permission: String): Boolean {
            calls += "grant $packageName $permission"
            return true
        }

        override fun setAppOp(packageName: String, op: String, mode: String): Boolean {
            calls += "appop $packageName $op $mode"
            return true
        }

        override fun writeFile(path: String, data: ByteArray): Boolean {
            calls += "write $path"
            return true
        }
    }

    private fun shell(backend: ElevatedBackend, vararg on: RiskyClass) =
        ElevatedShell(backend, backend) { ElevatedChoice.SHIZUKU_APP }.also { it.risk = RiskyGate { risk -> risk in on } }

    private val game = "com.example.emulator"
    private val file = "/storage/emulated/0/Android/data/com.example.emulator/files/config.ini"

    @Test
    fun `everything is off until switched on`() {
        assertFalse(RiskySwitches.OFF.master)
        RiskyClass.entries.forEach { assertFalse(RiskySwitches.OFF.allows(it)) }
    }

    @Test
    fun `a class needs the master as well as its own switch`() {
        val classOnly = RiskySwitches(master = false, classes = RiskyClass.entries.toSet())
        RiskyClass.entries.forEach { assertFalse(classOnly.allows(it)) }

        val masterOnly = RiskySwitches(master = true)
        RiskyClass.entries.forEach { assertFalse(masterOnly.allows(it)) }

        val both = RiskySwitches(master = true, classes = setOf(RiskyClass.OTHER_APP_FILES))
        assertTrue(both.allows(RiskyClass.OTHER_APP_FILES))
        assertFalse(both.allows(RiskyClass.GRANT_ACCESS))
        assertFalse(both.allows(RiskyClass.ROOT_COMMANDS))
    }

    @Test
    fun `the switches are not loaded means no`() {
        // RiskyActions answers from memory and is false before anything has loaded it.
        RiskyClass.entries.forEach { assertFalse(RiskyActions.allows(it)) }
    }

    @Test
    fun `granting is refused unless the class is on`() {
        val backend = RecordingBackend()
        val off = shell(backend)
        assertFalse(off.grantPermission(game, "android.permission.CAMERA"))
        assertFalse(off.setAppOp(game, RiskyPrompts.ALL_FILES_OP, "allow"))
        assertTrue(backend.calls.isEmpty())

        val wrongClass = shell(backend, RiskyClass.OTHER_APP_FILES)
        assertFalse(wrongClass.setAppOp(game, RiskyPrompts.ALL_FILES_OP, "allow"))
        assertTrue(backend.calls.isEmpty())

        val on = shell(backend, RiskyClass.GRANT_ACCESS)
        assertTrue(on.grantPermission(game, "android.permission.CAMERA"))
        assertTrue(on.setAppOp(game, RiskyPrompts.ALL_FILES_OP, "allow"))
        assertEquals(listOf("grant $game android.permission.CAMERA", "appop $game MANAGE_EXTERNAL_STORAGE allow"), backend.calls)
    }

    @Test
    fun `an appop is checked before it reaches the helper`() {
        val backend = RecordingBackend()
        val on = shell(backend, RiskyClass.GRANT_ACCESS)
        assertFalse(on.setAppOp(game, "manage_external_storage", "allow"))
        assertFalse(on.setAppOp(game, "MANAGE_EXTERNAL_STORAGE; rm", "allow"))
        assertFalse(on.setAppOp(game, "MANAGE_EXTERNAL_STORAGE", "foreground"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `writing another app's file is refused unless the class is on`() {
        val backend = RecordingBackend()
        assertFalse(shell(backend).writeFile(file, byteArrayOf(1)))
        assertFalse(shell(backend, RiskyClass.GRANT_ACCESS).writeFile(file, byteArrayOf(1)))
        assertTrue(backend.calls.isEmpty())

        assertTrue(shell(backend, RiskyClass.OTHER_APP_FILES).writeFile(file, byteArrayOf(1)))
        assertEquals(listOf("write $file"), backend.calls)
    }

    @Test
    fun `a path outside shared storage is refused even with the class on`() {
        val backend = RecordingBackend()
        assertFalse(shell(backend, RiskyClass.OTHER_APP_FILES).writeFile("/data/data/com.example.emulator/cores/x.so", byteArrayOf(1)))
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `each confirmation names the app and the exact thing`() {
        val files = RiskyPrompts.allFilesConfirm("NetherSX2", "xyz.aethersx2.tturnip")
        assertTrue("NetherSX2" in files && "xyz.aethersx2.tturnip" in files && RiskyPrompts.ALL_FILES_OP in files)

        val permission = RiskyPrompts.permissionConfirm("Dolphin", "org.dolphinemu.dolphinemu", "android.permission.RECORD_AUDIO")
        assertTrue("Dolphin" in permission && "android.permission.RECORD_AUDIO" in permission)

        val write = RiskyPrompts.writeFileConfirm("RetroArch", "/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg")
        assertTrue("RetroArch" in write && "retroarch.cfg" in write)

        val core = RiskyPrompts.placeCoreConfirm("mgba", "RetroArch", "/data/user/0/com.retroarch/cores/mgba_libretro_android.so")
        assertTrue("mgba_libretro_android.so" in core && "root" in core)

        val cores = RiskyPrompts.placeCoresConfirm((1..6).map { "/cores/c$it.so" }, "RetroArch")
        assertTrue("6 cores" in cores && "and 2 more" in cores)
    }

    @Test
    fun `no sentence uses the double dash an XML comment forbids or a Shizuku name`() {
        val all = RiskyClass.entries.flatMap { listOf(it.title, it.summary, RiskyPrompts.turnOnHint(it)) } +
            RiskyPrompts.allFilesTitle("A") + RiskyPrompts.allFilesConfirm("A", "a.b") + RiskyPrompts.permissionTitle("A", "camera")
        all.forEach { assertFalse(it, " -- " in it) }
        all.forEach { assertFalse(it, "Shizuku" in it) }
    }
}
