package dev.droidtop.runtime

/**
 * How the Linux desktop looks against droidtop's own Light or dark (docs/SPEC.md 2b "Desktop appearance",
 * Droidtop/tracker#694): its background, which droidtop's sway config sets, and the dark or light look of the
 * container's GTK apps, which [ContainerLayout.clientEnvironment] gives every program it starts.
 */
object DesktopAppearance {
    /**
     * Whether the desktop starts dark: droidtop's Light or dark, resolved by the session service as the desktop
     * starts. Read when a program's environment is built, so a change applies from the next desktop start, as
     * every Desktop setting that changes the container does.
     */
    @Volatile
    var dark: Boolean = true

    /**
     * The environment that puts GTK apps in [dark] or light. GTK 3 takes `GTK_THEME` (Adwaita is built into GTK,
     * so every image that has GTK has it), and libadwaita (GTK 4) takes its colour scheme from
     * `ADW_DEBUG_COLOR_SCHEME`, since there is no settings portal in the container to ask. Light needs only the
     * second: Adwaita is light already.
     */
    fun environment(dark: Boolean): Map<String, String> =
        if (dark) {
            mapOf("GTK_THEME" to "Adwaita:dark", "ADW_DEBUG_COLOR_SCHEME" to "prefer-dark")
        } else {
            mapOf("ADW_DEBUG_COLOR_SCHEME" to "prefer-light")
        }

    /** The desktop's background colours. [MATCH], the default, is droidtop's own ground in light or dark. */
    enum class Background(val id: String, val label: String, private val darkColor: String, private val lightColor: String) {
        MATCH("match", "Match droidtop", "#1E1F24", "#ECEDF1"),
        BLACK("black", "Black", "#000000", "#000000"),
        SLATE("slate", "Slate", "#2B3440", "#2B3440"),
        BLUE("blue", "Deep blue", "#14284B", "#14284B"),
        GREEN("green", "Forest", "#1D3A2C", "#1D3A2C"),
        ;

        /** The colour as sway's `output bg` takes it. */
        fun color(dark: Boolean): String = if (dark) darkColor else lightColor

        companion object {
            val DEFAULT = MATCH

            fun fromId(id: String?): Background = entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}
