package dev.droidtop.runtime.launcherimport

/**
 * What an intent from a Nova or Apex backup says, read from its `Intent.toUri` text (`#Intent;action=...;
 * component=...;S.LAUNCHER_ACTION=...;end`), Nova's named form (`<title>$<uri>`), or a bare flattened component.
 * Only the fields an import uses: the component, the action, the categories and the launcher's own action extra. An
 * intent is data here: the importer keeps launcher intents for installed apps and nothing else. Pure.
 */
data class IntentUri(
    val component: String?,
    val action: String?,
    val categories: Set<String>,
    /** `LAUNCHER_ACTION`, the extra Nova's and Apex's own actions carry (`APP_DRAWER`, `SCREEN_LOCK`, ...). */
    val launcherAction: String?,
) {
    /** A plain app launch: MAIN, or no action, aimed at a component. */
    val isAppLaunch: Boolean
        get() = component != null && launcherAction == null && (action == null || action == "android.intent.action.MAIN")

    companion object {
        fun parse(text: String?): IntentUri? {
            val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // Nova's named intent: "<title>$<uri>".
            val uri = raw.substringAfter('$', raw).let { if (it.isEmpty()) raw else it }
            val start = uri.indexOf("#Intent;")
            if (start < 0) {
                // A bare component ("pkg/.Main" or "pkg/pkg.Main").
                return normaliseComponent(uri)?.let { IntentUri(it, null, emptySet(), null) }
            }
            val body = uri.substring(start + "#Intent;".length).substringBefore(";end")
            var component: String? = null
            var action: String? = null
            val categories = mutableSetOf<String>()
            var launcherAction: String? = null
            for (part in body.split(';')) {
                val key = part.substringBefore('=')
                val value = decode(part.substringAfter('=', ""))
                when {
                    key == "component" -> component = normaliseComponent(value)
                    key == "action" -> action = value
                    key == "category" -> categories += value
                    key.endsWith(".LAUNCHER_ACTION") || key == "S.LAUNCHER_ACTION" -> launcherAction = value
                }
            }
            return IntentUri(component, action, categories, launcherAction)
        }

        /** `pkg/.Cls` (the short form) and `pkg/pkg.Cls` both become `pkg/pkg.Cls`; anything else is null. */
        fun normaliseComponent(text: String?): String? {
            val value = text?.trim() ?: return null
            val slash = value.indexOf('/')
            if (slash <= 0 || slash == value.length - 1) return null
            val pkg = value.substring(0, slash)
            val cls = value.substring(slash + 1)
            if (!PACKAGE.matches(pkg) || cls.any { it.isWhitespace() }) return null
            return "$pkg/" + if (cls.startsWith(".")) pkg + cls else cls
        }

        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        /** Intent.toUri's escaping: %xx for the reserved characters. */
        private fun decode(value: String): String =
            if ('%' !in value) value else runCatching { java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
    }
}
