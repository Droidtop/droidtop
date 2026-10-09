package dev.droidtop.runtime.linux.noroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostAudioServerTest {
    @Test
    fun `x86_64 picks droidtop's own fixed-name asset, not the arm64 one`() {
        val assets = listOf("pulseaudio-gamenative-x86_64.tzst", "pulseaudio-gamenative-20260612.tzst", "other.png")
        assertEquals("pulseaudio-gamenative-x86_64.tzst", HostAudioServer.assetNameFor(assets, "x86_64"))
    }

    @Test
    fun `arm64 picks the date-stamped upstream asset by pattern, not a hardcoded date`() {
        val assets = listOf("pulseaudio-gamenative-x86_64.tzst", "pulseaudio-gamenative-20260612.tzst")
        assertEquals("pulseaudio-gamenative-20260612.tzst", HostAudioServer.assetNameFor(assets, "arm64-v8a"))

        val resynced = listOf("pulseaudio-gamenative-20270103.tzst")
        assertEquals("pulseaudio-gamenative-20270103.tzst", HostAudioServer.assetNameFor(resynced, "arm64-v8a"))
    }

    @Test
    fun `an unpackaged ABI or a missing asset is null, not a guess`() {
        assertNull(HostAudioServer.assetNameFor(emptyList(), "x86_64"))
        assertNull(HostAudioServer.assetNameFor(listOf("pulseaudio-gamenative-20260612.tzst"), "armeabi-v7a"))
        // The arm64 asset name must not also satisfy the x86_64 lookup or vice versa.
        assertNull(HostAudioServer.assetNameFor(listOf("pulseaudio-gamenative-20260612.tzst"), "x86_64"))
        assertNull(HostAudioServer.assetNameFor(listOf("pulseaudio-gamenative-x86_64.tzst"), "arm64-v8a"))
    }

    @Test
    fun `arm64 adds the Desktop's own modules asset, x86_64 needs none`() {
        val assets = listOf(
            "pulseaudio-gamenative-x86_64.tzst",
            "pulseaudio-gamenative-20260612.tzst",
            "pulseaudio-desktop-arm64-v8a.tzst",
        )
        assertEquals("pulseaudio-desktop-arm64-v8a.tzst", HostAudioServer.desktopAssetNameFor(assets, "arm64-v8a"))
        assertNull(HostAudioServer.desktopAssetNameFor(assets, "x86_64"))
        assertNull(HostAudioServer.desktopAssetNameFor(listOf("pulseaudio-gamenative-20260612.tzst"), "arm64-v8a"))
    }

    @Test
    fun `the config loads the unix socket transport and the AAudio sink`() {
        val config = HostAudioServer.defaultPaConfig("/data/user/0/dev.droidtop.app/files/proot/sockets/audio.sock")
        assertEquals(
            "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=false " +
                "socket=\"/data/user/0/dev.droidtop.app/files/proot/sockets/audio.sock\"\n" +
                "load-module module-aaudio-sink\n",
            config,
        )
    }

    @Test
    fun `the microphone adds a pipe source and makes it the default`() {
        val config = HostAudioServer.defaultPaConfig("/s/audio.sock", "/w/mic.pipe")
        assertEquals(
            "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=false socket=\"/s/audio.sock\"\n" +
                "load-module module-aaudio-sink\n" +
                "load-module module-pipe-source source_name=droidtop_mic file=\"/w/mic.pipe\" format=s16le rate=48000 channels=1\n" +
                "set-default-source droidtop_mic\n",
            config,
        )
    }
}
