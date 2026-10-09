package dev.droidtop.runtime

import dev.droidtop.runtime.systemstatus.CpuTimes
import dev.droidtop.runtime.systemstatus.FrameRate
import dev.droidtop.runtime.systemstatus.GameMode
import dev.droidtop.runtime.systemstatus.GameModeControl
import dev.droidtop.runtime.systemstatus.OverlayLevel
import dev.droidtop.runtime.systemstatus.OverlayReadings
import dev.droidtop.runtime.systemstatus.PerformanceOverlayModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceOverlayModelTest {
    /** [count] frames [gapNs] apart ending at [endNs], as `--latency` rows (desired, actual, ready). */
    private fun latency(count: Int, gapNs: Long, endNs: Long): List<String> =
        listOf("16666667") + (0 until count).map { i ->
            val t = endNs - (count - 1 - i) * gapNs
            "${t - 1000} $t ${t + 500}"
        }

    @Test
    fun `levels cycle Off FPS Basic Full and wrap`() {
        assertEquals(OverlayLevel.FPS, OverlayLevel.OFF.next())
        assertEquals(OverlayLevel.BASIC, OverlayLevel.FPS.next())
        assertEquals(OverlayLevel.FULL, OverlayLevel.BASIC.next())
        assertEquals(OverlayLevel.OFF, OverlayLevel.FULL.next())
        assertEquals(OverlayLevel.OFF, OverlayLevel.fromKey("nonsense"))
        assertEquals(OverlayLevel.BASIC, OverlayLevel.fromKey("basic"))
    }

    @Test
    fun `sixty frames a second read as 60 with a 16 point 7 ms frame`() {
        val end = 50_000_000_000L
        val rate = PerformanceOverlayModel.parseLatency(latency(120, 16_666_667L, end), nowNs = end + 10_000_000L)!!
        assertEquals(60, rate.fps)
        assertEquals(16.67, rate.avgFrameMs, 0.05)
        assertEquals(16.67, rate.worstFrameMs, 0.05)
    }

    @Test
    fun `thirty frames a second and a hitch show in the worst frame`() {
        val end = 50_000_000_000L
        val rows = latency(40, 33_333_333L, end).toMutableList()
        // One dropped frame in the middle is a gap of two frame times.
        rows.removeAt(20)
        val rate = PerformanceOverlayModel.parseLatency(rows, nowNs = end)!!
        assertTrue(rate.worstFrameMs > 60.0)
        assertTrue(rate.fps in 28..31)
    }

    @Test
    fun `rows that were never presented are ignored and too few real rows is no reading`() {
        val never = Long.MAX_VALUE
        val rows = listOf("16666667", "0 $never $never", "1 $never 3")
        assertNull(PerformanceOverlayModel.parseLatency(rows, nowNs = 1L))
        assertNull(PerformanceOverlayModel.parseLatency(emptyList(), nowNs = 1L))
    }

    @Test
    fun `a layer that stopped presenting two seconds ago is zero frames a second`() {
        val end = 50_000_000_000L
        val rate = PerformanceOverlayModel.parseLatency(latency(120, 16_666_667L, end), nowNs = end + 3_000_000_000L)!!
        assertEquals(0, rate.fps)
    }

    @Test
    fun `the game's surface view layer wins over its window and helper layers`() {
        val listing = """
            Background for SurfaceView[com.game/com.game.Main](BLAST)#4
            com.game/com.game.Main#2
            SurfaceView[com.game/com.game.Main](BLAST)#4
            com.other/com.other.Main#9
        """.trimIndent()
        assertEquals("SurfaceView[com.game/com.game.Main](BLAST)#4", PerformanceOverlayModel.pickLayer(listing, "com.game"))
        assertEquals("com.game/com.game.Main#2", PerformanceOverlayModel.pickLayer("com.game/com.game.Main#2\nBounds for - com.game/com.game.Main#2", "com.game"))
        assertNull(PerformanceOverlayModel.pickLayer(listing, "com.missing"))
        assertNull(PerformanceOverlayModel.pickLayer(null, "com.game"))
    }

    @Test
    fun `the probe output splits into sections and each is read`() {
        val end = 9_000_000_000L
        val output = buildString {
            appendLine("#lat")
            latency(30, 16_666_667L, end).forEach(::appendLine)
            appendLine("#cpu")
            appendLine("cpu  100 0 50 800 50 0 0 0 0 0")
            appendLine("#gpu")
            appendLine("37 %")
            appendLine("#temp")
            appendLine("cpuss-0-usr 45000")
            appendLine("cpuss-1-usr 61500")
            appendLine("gpuss-0-usr 52300")
            appendLine("battery 31000")
            appendLine("nonsense")
        }
        val shell = PerformanceOverlayModel.parseProbe(output, nowNs = end)
        assertEquals(CpuTimes(total = 1000, idle = 850), shell.cpuTimes)
        assertEquals(37, shell.gpuPercent)
        assertEquals(615, shell.cpuTempTenthC)
        assertEquals(523, shell.gpuTempTenthC)
        assertNotNull(shell.frameRate)
    }

    @Test
    fun `a probe that read nothing leaves every reading null`() {
        val shell = PerformanceOverlayModel.parseProbe("", nowNs = 1L)
        assertNull(shell.frameRate)
        assertNull(shell.cpuTimes)
        assertNull(shell.gpuPercent)
        assertNull(shell.cpuTempTenthC)
    }

    @Test
    fun `the probe is one argv that passes the layer and the level as arguments, never inside the script`() {
        val argv = PerformanceOverlayModel.probeArgv("SurfaceView[com.x/y](BLAST)#1; rm -rf /", OverlayLevel.FULL)
        assertEquals(listOf("sh", "-c"), argv.take(2))
        assertFalse("rm -rf" in argv[2])
        assertEquals(listOf("sh", "SurfaceView[com.x/y](BLAST)#1; rm -rf /", "full"), argv.drop(3))
    }

    private val readings = OverlayReadings(
        frameRate = FrameRate(58, 17.2, 31.0),
        cpuPercent = 34,
        gpuPercent = 51,
        batteryPercent = 83,
        charging = true,
        memUsedMb = 5120,
        memTotalMb = 12288,
        cpuTempTenthC = 615,
        gpuTempTenthC = 523,
        batteryTempTenthC = 365,
    )

    @Test
    fun `each level draws more than the one below`() {
        assertEquals(emptyList<String>(), PerformanceOverlayModel.lines(OverlayLevel.OFF, readings))
        assertEquals(listOf("FPS 58"), PerformanceOverlayModel.lines(OverlayLevel.FPS, readings))
        assertEquals(listOf("FPS 58", "CPU 34%", "GPU 51%", "BAT 83% +"), PerformanceOverlayModel.lines(OverlayLevel.BASIC, readings))
        val full = PerformanceOverlayModel.lines(OverlayLevel.FULL, readings)
        assertEquals(7, full.size)
        assertEquals("Frame 17.2 ms (worst 31.0)", full[4])
        assertEquals("CPU 61.5 C   GPU 52.3 C   BAT 36.5 C", full[5])
        assertEquals("RAM 5120 / 12288 MB", full[6])
    }

    @Test
    fun `what nobody could read is drawn as dashes and not as a number`() {
        assertEquals(listOf("FPS --", "CPU --", "GPU --", "BAT --"), PerformanceOverlayModel.lines(OverlayLevel.BASIC, OverlayReadings()))
        val full = PerformanceOverlayModel.lines(OverlayLevel.FULL, OverlayReadings())
        assertEquals("Frame --", full[4])
        assertEquals("RAM --", full[6])
    }

    @Test
    fun `cpu load is the busy share between two probe passes`() {
        val before = CpuTimes(total = 1000, idle = 800)
        val shell = dev.droidtop.runtime.systemstatus.ShellReadings(cpuTimes = CpuTimes(total = 2000, idle = 1300))
        assertEquals(50, PerformanceOverlayModel.readings(null, shell, before).cpuPercent)
        assertNull(PerformanceOverlayModel.readings(null, shell, null).cpuPercent)
    }

    @Test
    fun `game mode tries Android 14's spelling and then 12 and 13's`() {
        val commands = GameModeControl.commands(GameMode.PERFORMANCE, "com.game")
        assertEquals(listOf("cmd", "game", "set", "--mode", "performance", "--user", "0", "com.game"), commands[0])
        assertEquals(listOf("cmd", "game", "mode", "--user", "0", "performance", "com.game"), commands[1])
        assertEquals(GameMode.PERFORMANCE, GameMode.STANDARD.next())
        assertEquals(GameMode.STANDARD, GameMode.BATTERY.next())
        assertTrue(GameModeControl.refused("Unknown command: set"))
        assertFalse(GameModeControl.refused(""))
    }
}
