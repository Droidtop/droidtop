package dev.droidtop.runtime.linux.noroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostAudioServerTest {
    @Test
    fun `each ABI picks droidtop's own asset named for it`() {
        val assets = listOf("pulseaudio-gamenative-x86_64.tzst", "pulseaudio-gamenative-arm64-v8a.tzst", "other.png")
        assertEquals("pulseaudio-gamenative-x86_64.tzst", HostAudioServer.assetNameFor(assets, "x86_64"))
        assertEquals("pulseaudio-gamenative-arm64-v8a.tzst", HostAudioServer.assetNameFor(assets, "arm64-v8a"))
    }

    @Test
    fun `an unpackaged ABI or a missing asset is null, not a guess`() {
        assertNull(HostAudioServer.assetNameFor(emptyList(), "x86_64"))
        assertNull(HostAudioServer.assetNameFor(listOf("pulseaudio-gamenative-armeabi-v7a.tzst"), "armeabi-v7a"))
        // One ABI's asset must not satisfy the other's lookup.
        assertNull(HostAudioServer.assetNameFor(listOf("pulseaudio-gamenative-arm64-v8a.tzst"), "x86_64"))
        assertNull(HostAudioServer.assetNameFor(listOf("pulseaudio-gamenative-x86_64.tzst"), "arm64-v8a"))
        // GameNative's old date-stamped arm64 asset is no longer droidtop's.
        assertNull(HostAudioServer.assetNameFor(listOf("pulseaudio-gamenative-20260612.tzst"), "arm64-v8a"))
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
