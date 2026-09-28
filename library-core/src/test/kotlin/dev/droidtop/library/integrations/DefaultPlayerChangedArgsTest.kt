package dev.droidtop.library.integrations

import dev.droidtop.library.consoles.Player
import dev.droidtop.library.consoles.libretroCoreId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The payload contract of [PluginEventBus]'s
 * [dev.droidtop.pluginhost.PluginEvent.DEFAULT_PLAYER_CHANGED]
 * notification (docs/SPEC.md 12a): a subscribed manager plugin -- the
 * real one is droidtop-plugin-retroarch -- decides from this map alone
 * whether the core the new player will launch with is installed, so the
 * keys and the null-to-empty normalisation are the contract, not an
 * implementation detail. The realistic case below is the rig-shaped
 * one: psx's configured core is `mednafen_psx` while the chosen
 * players-database entry launches `mednafen_psx_hw`, and the payload
 * must carry the entry's own core (see [LibretroCoreIdTest] for the
 * resolution itself).
 */
class DefaultPlayerChangedArgsTest {
    // players-database.json's real psx entry, verbatim (pinned
    // droidtop-platforms c74eba36).
    private val beetlePsxHw = Player.AmStart(
        id = "psx-com-retroarch-retroarch-beetle-psx-hw",
        name = "Retroarch - beetle psx hw",
        argumentsTemplate = "-n com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture " +
            "-e ROM {file.path} " +
            "-e LIBRETRO /data/data/com.retroarch/cores/mednafen_psx_hw_libretro_android.so " +
            "-e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg " +
            "-e QUITFOCUS --activity-clear-task --activity-clear-top",
        packageName = "com.retroarch",
        killPackageProcesses = true,
    )

    @Test
    fun `a RetroArch choice carries the package and the entry's own core`() {
        val args = defaultPlayerChangedArgs(
            systemId = "psx",
            systemName = "Sony PlayStation",
            playerId = beetlePsxHw.id,
            playerName = beetlePsxHw.name,
            playerPackage = beetlePsxHw.packageName,
            core = libretroCoreId(beetlePsxHw, systemConfiguredCore = "mednafen_psx"),
        )
        assertEquals(
            mapOf(
                "systemId" to "psx",
                "systemName" to "Sony PlayStation",
                "playerId" to "psx-com-retroarch-retroarch-beetle-psx-hw",
                "playerName" to "Retroarch - beetle psx hw",
                "playerPackage" to "com.retroarch",
                "core" to "mednafen_psx_hw",
            ),
            args,
        )
    }

    @Test
    fun `a choice with no package and no core arrives as empty strings, never nulls`() {
        // A plugin reads args through PluginArgs, whose string accessor
        // has no null -- an absent fact must be "", or the payload would
        // smuggle the string "null" (the exact PluginRecord round-trip
        // bug shape, dq-plugins-01) or crash a reader.
        assertEquals(
            mapOf(
                "systemId" to "psx",
                "systemName" to "Sony PlayStation",
                "playerId" to "am-template",
                "playerName" to "Bare am start",
                "playerPackage" to "",
                "core" to "",
            ),
            defaultPlayerChangedArgs(
                systemId = "psx",
                systemName = "Sony PlayStation",
                playerId = "am-template",
                playerName = "Bare am start",
                playerPackage = null,
                core = null,
            ),
        )
    }
}
