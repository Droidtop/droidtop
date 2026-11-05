package dev.droidtop.pluginhost

/**
 * A plugin's failure in droidtop's voice (Droidtop/tracker#852 part 2 slice 2, from #883 §3.7, approved 2026-11-05).
 * The owner: plugins "feel hacked on". Failures said who failed ("<label> ran into a problem it didn't explain", "It
 * could not do that", "Couldn't finish"), not what the person can do. Now every failure has a code from a fixed list,
 * and droidtop words it, with its one fix. The subject is the feature's own name, the one it has in Game sources
 * (a source's or store's label), never "the plugin". What the plugin said goes to Info, always. Pure.
 */
object PluginFailureWords {
    /** The one thing the person can do about it, drawn by droidtop as the notice's button. */
    enum class Fix(val label: String) {
        SIGN_IN("Sign in"),
        OPEN_SITE("Open the site"),
        TRY_AGAIN("Try again"),
        WAIT("Waiting"),
        ALLOW("Allow"),
        UPDATE("Update"),
    }

    data class Words(val title: String, val fix: Fix)

    /** The title (at most 40 characters for a short name) and the fix for [code], about the feature called [name]. */
    fun of(code: PluginErrorCode?, name: String): Words = when (code) {
        PluginErrorCode.SIGN_IN_REQUIRED -> Words("$name wants you to sign in", Fix.SIGN_IN)
        PluginErrorCode.BROWSER_CHECK_REQUIRED -> Words("$name checks your browser first", Fix.OPEN_SITE)
        PluginErrorCode.OFFLINE, PluginErrorCode.SITE_DOWN -> Words("$name can't be reached", Fix.TRY_AGAIN)
        PluginErrorCode.RATE_LIMITED -> Words("$name asked droidtop to wait", Fix.WAIT)
        PluginErrorCode.PERMISSION_DENIED -> Words("$name can't do this yet", Fix.ALLOW)
        PluginErrorCode.TIMEOUT -> Words("$name didn't answer in time", Fix.TRY_AGAIN)
        PluginErrorCode.NEEDS_UPDATE -> Words("$name needs its update", Fix.UPDATE)
        else -> Words("$name stopped", Fix.TRY_AGAIN)
    }

    /** An install or update that did not happen: the outcome first (#883 §3.7), the machinery words left for Info. */
    fun notUpdated(name: String): String = "$name wasn't updated"
}
