package dev.droidtop.hostbridge

import dev.droidtop.runtime.DisplayOutput

/**
 * One compositor output the desktop session uses and the Android display it is for (Droidtop/tracker#908, step D2).
 * [outputName] is the compositor's name for it ("HEADLESS-2"); the primary's is "" while the compositor has not
 * named it, which host-bridge's calls take as the primary all the same.
 */
data class DesktopOutput(val display: DisplayOutput, val outputName: String)

/**
 * The rules of the desktop's extra outputs (Droidtop/tracker#908, step D2), pure so they are tested on the JVM
 * (DesktopOutputsTest): which name is the output sway just made, where it goes in the layout, the sway commands, and
 * the session's list of outputs, the primary first and never replaced or removed.
 */
object DesktopOutputs {
    /** sway's command for a new headless output (sway/commands/create_output.c; headless backend only). */
    const val CREATE_COMMAND = "create_output"

    /** The names sway gives outputs ("HEADLESS-2"): nothing that could end a command or start another. */
    private val OUTPUT_NAME = Regex("[A-Za-z0-9_.-]+")

    fun isOutputName(name: String): Boolean = OUTPUT_NAME.matches(name)

    /** The output that is in [after] and was not in [before] (host-bridge's [HostBridge.outputNames]); null for none. */
    fun newOutputName(before: List<String>, after: List<String>): String? =
        after.firstOrNull { it !in before && isOutputName(it) }

    /**
     * Where [name] goes across the layout: just right of every other output, which with the primary alone is just
     * right of the primary, top edges level. In logical pixels, from each output's box as wlroots works it out
     * ([OutputPlacement.box]); 0 when no other output is in the layout yet.
     */
    fun positionX(placements: List<OutputPlacement>, name: String): Int =
        placements.filter { it.name != name }.mapNotNull { it.box() }.maxOfOrNull { it.x + it.width } ?: 0

    /** Whether the compositor reports [name] at [width] x [height] pixels, the size it was asked for. */
    fun sized(placements: List<OutputPlacement>, name: String, width: Int, height: Int): Boolean =
        placements.any { it.name == name && it.enabled && it.modeWidth == width && it.modeHeight == height }

    fun positionCommand(name: String, x: Int): String {
        require(isOutputName(name)) { "not an output name: $name" }
        return "output $name pos $x 0"
    }

    /** sway's command that removes a headless output it made (sway/commands/output/unplug.c). */
    fun unplugCommand(name: String): String {
        require(isOutputName(name)) { "not an output name: $name" }
        return "output $name unplug"
    }

    /** The extra output for the Android display [displayId] in [outputs], if it has one; never the primary. */
    fun outputFor(outputs: List<DesktopOutput>, displayId: Int): DesktopOutput? =
        outputs.drop(1).firstOrNull { it.display.androidDisplayId == displayId }

    /**
     * [outputs] with [display] on [outputName]: its entry replaced if it had one, else added last. The primary stays,
     * and its own display is never given an extra output.
     */
    fun extended(outputs: List<DesktopOutput>, display: DisplayOutput, outputName: String): List<DesktopOutput> {
        if (outputs.isEmpty() || display.androidDisplayId == outputs.first().display.androidDisplayId) return outputs
        val entry = DesktopOutput(display, outputName)
        val rest = outputs.drop(1)
        val at = rest.indexOfFirst { it.display.androidDisplayId == display.androidDisplayId }
        return listOf(outputs.first()) + if (at < 0) rest + entry else rest.toMutableList().also { it[at] = entry }
    }

    /** [outputs] without the extra output of the Android display [displayId]; unchanged when it has none. */
    fun retracted(outputs: List<DesktopOutput>, displayId: Int): List<DesktopOutput> =
        outputs.take(1) + outputs.drop(1).filter { it.display.androidDisplayId != displayId }
}
