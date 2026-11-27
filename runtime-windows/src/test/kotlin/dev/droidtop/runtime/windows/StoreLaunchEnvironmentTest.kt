package dev.droidtop.runtime.windows

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.*
import org.junit.Test

class StoreLaunchEnvironmentTest {
    @Test
    fun `store values survive the actual runtime encoding as literal environment values`() {
        val values = mapOf("EALaunchEAID" to "A person", "OriginSessionKey" to "a=b\\c;\"quoted\"")
        val env = EnvVars(storeEnvironmentText(values))
        values.forEach { (key, value) -> assertEquals(value, env.get(key)) }
        assertEquals("", storeEnvironmentText(emptyMap()))
    }

    @Test
    fun `store variables cannot change the host loader prefix or split an environment entry`() {
        for ((name, value) in listOf("LD_PRELOAD" to "x", "WINEPREFIX" to "other", "PATH" to "other", "BAD=NAME" to "x", "TOKEN" to "one\ntwo")) {
            try { storeEnvironmentText(mapOf(name to value)); fail("$name must be refused") } catch (_: IllegalArgumentException) { }
        }
    }
}
