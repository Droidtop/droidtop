package dev.droidtop.library.lutris

import dev.droidtop.library.PcPrefixState

/**
 * What applying an import would really change in one prefix: the
 * [changes] that differ from what the prefix is already set to, a line
 * for each, and the requests it already meets. The preview draws exactly
 * this, and Apply writes exactly [changes] -- nothing the person was not
 * shown (threat model, decision 7).
 */
data class PrefixPlan(
    val changes: WinePrefixChanges,
    val lines: List<ImportLine>,
    val alreadySet: List<ImportLine>,
)

fun WinePrefixChanges.against(state: PcPrefixState): PrefixPlan {
    val lines = mutableListOf<ImportLine>()
    val already = mutableListOf<ImportLine>()

    val dxvkChange = dxvk?.takeIf { it != state.dxvk }
    dxvk?.let { wanted ->
        if (dxvkChange != null) {
            lines += ImportLine("DXVK", if (wanted) "Turn on" else "Turn off: Direct3D goes through Wine's own WineD3D")
        } else {
            already += ImportLine("DXVK", if (wanted) "Already on" else "Already off")
        }
    }
    val esyncChange = esync?.takeIf { it != state.esync }
    esync?.let { wanted ->
        if (esyncChange != null) {
            lines += ImportLine("Esync", if (wanted) "Turn on" else "Turn off")
        } else {
            already += ImportLine("Esync", if (wanted) "Already on" else "Already off")
        }
    }
    val newComponents = components.filterNot { it in state.components }.toSet()
    components.forEach { id ->
        val label = "Windows component: ${componentLabel(id)}"
        if (id in newComponents) lines += ImportLine(label, "Switch on") else already += ImportLine(label, "Already on")
    }
    val newOverrides = dllOverrides.filter { (dll, mode) -> state.dllOverrides[dll] != mode }
    dllOverrides.forEach { (dll, mode) ->
        val label = "DLL override: $dll"
        if (dll in newOverrides) {
            lines += ImportLine(label, overrideLabel(mode) + (state.dllOverrides[dll]?.let { " (now ${overrideLabel(it)})" } ?: ""))
        } else {
            already += ImportLine(label, "Already ${overrideLabel(mode)}")
        }
    }
    val newEnv = env.filter { (name, value) -> state.env[name] != value }
    env.forEach { (name, value) ->
        val label = "Environment: $name"
        if (name in newEnv) {
            lines += ImportLine(label, "Set to $value" + (state.env[name]?.let { " (now $it)" } ?: ""))
        } else {
            already += ImportLine(label, "Already $value")
        }
    }
    return PrefixPlan(WinePrefixChanges(dxvkChange, esyncChange, newComponents, newOverrides, newEnv), lines, already)
}

/** gamenative's component ids, named for a person. */
fun componentLabel(id: String): String = when (id) {
    "direct3d" -> "Direct3D extras (d3dx9, d3dx10, d3dx11, d3dcompiler)"
    "directsound" -> "DirectSound"
    "directinput8" -> "DirectInput 8"
    "directinput" -> "DirectInput"
    "directmusic" -> "DirectMusic"
    "directshow" -> "DirectShow"
    "directplay" -> "DirectPlay"
    "xaudio" -> "XAudio and XACT"
    "vcrun2010" -> "Visual C++ 2010 runtime"
    else -> id
}

private fun overrideLabel(mode: String): String = when (mode) {
    "n" -> "the game's own DLL"
    "b" -> "Wine's own DLL"
    "n,b" -> "the game's own DLL, else Wine's"
    "b,n" -> "Wine's own DLL, else the game's"
    "" -> "disabled"
    else -> mode
}

/**
 * Wine's `WINEDLLOVERRIDES` (`a,b=n,b;c=`) as one mode per DLL, and back.
 * Entries are `;`-separated; one entry can name several DLLs with `,`
 * before its `=`. Pure, shared by the preview and the write.
 */
object DllOverrides {
    fun parse(value: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (entry in value.split(';')) {
            val eq = entry.indexOf('=')
            if (eq < 0) continue
            val mode = entry.substring(eq + 1).trim()
            entry.substring(0, eq).split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out[it] = mode }
        }
        return out
    }

    fun format(overrides: Map<String, String>): String =
        overrides.entries.joinToString(";") { (dll, mode) -> "$dll=$mode" }
}
