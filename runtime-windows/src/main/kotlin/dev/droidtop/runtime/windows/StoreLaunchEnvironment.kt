package dev.droidtop.runtime.windows

import com.winlator.core.envvars.EnvVars

/** The runtime's own escaping, not a shell command. Refuse host-loader overrides and values with control characters. */
internal fun storeEnvironmentText(values: Map<String, String>): String {
    val env = EnvVars()
    for ((name, value) in values) {
        val upper = name.uppercase()
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !upper.startsWith("LD_") && !upper.startsWith("DYLD_") &&
            upper !in setOf("PATH", "HOME", "WINEPREFIX", "WINELOADER", "WINESERVER") && value.none { it.code < 32 || it.code == 127 }) {
            "The store supplied an invalid launch environment variable"
        }
        env.put(name, value)
    }
    return env.toString()
}
