package dev.droidtop.hostbridge

/**
 * Which bar lists a window, and where it can be moved (Droidtop/tracker#908, step D4; docs/SPEC.md 2b "The desktop on
 * a second screen"). Pure, so the rules are tested on the JVM (WindowOutputsTest).
 *
 * [outputs] is the desktop's outputs by the compositor's name, the primary's first, as the session lists them
 * ([DesktopOutput.outputName]); the primary's may be "" while the compositor has not named it. A window's output is
 * [Toplevel.output], which host-bridge's native client keeps from wlr-foreign-toplevel-management's output_enter and
 * output_leave (output_match.h's windowOutput: on two outputs at once, the one it entered last).
 */
object WindowOutputs {
    /**
     * The index in [outputs] of the bar that lists a window on [windowOutput]: an added output's own bar when the
     * window is on it, otherwise the primary's (0). So a window whose output is not known, not named, or gone is on
     * the primary's bar, and with one output every window is, as before there was a second.
     */
    fun barFor(windowOutput: String, outputs: List<String>): Int {
        if (windowOutput.isEmpty()) return 0
        val at = outputs.indexOf(windowOutput)
        return if (at > 0) at else 0
    }

    /** The [windows] the bar of [outputs]`[bar]` lists, in their order; none for a bar that is not one of them. */
    fun <T> onBar(windows: List<T>, outputOf: (T) -> String, outputs: List<String>, bar: Int): List<T> {
        if (outputs.size <= 1) return if (bar == 0) windows else emptyList()
        if (bar !in outputs.indices) return emptyList()
        return windows.filter { barFor(outputOf(it), outputs) == bar }
    }

    /**
     * Where a window on [windowOutput] can be moved: every output but the one whose bar lists it, in [outputs]' order,
     * each with its screen's name from [screenNames] (the same order as [outputs]). Empty with one output, so the
     * action is not offered at all, and an output without a name yet is left out, since sway is told the name.
     */
    fun moveTargets(windowOutput: String, outputs: List<String>, screenNames: List<String>): List<MoveTarget> {
        if (outputs.size <= 1) return emptyList()
        val here = barFor(windowOutput, outputs)
        return outputs.indices
            .filter { it != here && DesktopOutputs.isOutputName(outputs[it]) }
            .map { MoveTarget(outputs[it], screenNames.getOrElse(it) { "" }) }
    }

    /**
     * The screen a window on [windowOutput] is shown on, by its name in [screenNames], for a list that holds the
     * windows of every screen (the window list a pad or Alt+Esc opens); null with one output, where it says nothing.
     */
    fun screenOf(windowOutput: String, outputs: List<String>, screenNames: List<String>): String? {
        if (outputs.size <= 1) return null
        return screenNames.getOrNull(barFor(windowOutput, outputs))?.takeIf { it.isNotBlank() }
    }
}

/** An output a window can be moved to: the compositor's name for it and its screen's name for people. */
data class MoveTarget(val outputName: String, val screenName: String)
