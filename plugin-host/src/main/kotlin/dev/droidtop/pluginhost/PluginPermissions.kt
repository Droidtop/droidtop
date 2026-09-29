package dev.droidtop.pluginhost

/** docs/plugin-api.md 4.1: how much the user must say yes to a permission. */
enum class PermissionTier { NORMAL, DANGEROUS, CRITICAL }

/**
 * One host permission: the registry row for docs/plugin-api.md 4.1. The
 * label is the plain-language wording the grant screen shows, identical in
 * every mode; PluginPermissionsTest checks every row against the table in
 * the document. [officialOnly] rows (marked with a dagger there) are
 * refused at install for an origin that is not the official one.
 * [scopedTier] is set where a parameter changes the tier: `apps.intents.out`
 * is normal for declared packages and dangerous for any package.
 */
data class PluginPermission(
    val id: String,
    val tier: PermissionTier,
    val label: String,
    val officialOnly: Boolean = false,
    val scopedTier: PermissionTier? = null,
)

/**
 * The permission registry (docs/plugin-api.md 4). Ids are closed and
 * host-defined; an id not in [all] is "not supported by this version of
 * droidtop", never a refusal. `provide:<point>` is the consent item for
 * providing a high-risk extension point (4.2), resolved through
 * [ExtensionPoints]. Ids a provider plugin declares in its own `exports`
 * are that plugin's and are not host-registry ids.
 */
object PluginPermissions {
    const val PROVIDE_PREFIX = "provide:"

    val all: List<PluginPermission> = listOf(
        PluginPermission("library.read", PermissionTier.NORMAL, "See your library: games, apps and systems"),
        PluginPermission("library.history", PermissionTier.DANGEROUS, "See what you play and for how long"),
        PluginPermission("library.folders.write", PermissionTier.DANGEROUS, "Add files to your game folders"),
        PluginPermission("saves.read", PermissionTier.DANGEROUS, "Read save data of your games"),
        PluginPermission("saves.write", PermissionTier.DANGEROUS, "Replace save data of your games"),
        PluginPermission("media.read", PermissionTier.DANGEROUS, "See your music, videos and pictures"),
        PluginPermission("perf.read", PermissionTier.NORMAL, "See performance readings (CPU, temperature, battery)"),
        PluginPermission("perf.profile.set", PermissionTier.DANGEROUS, "Change performance and fan settings"),
        PluginPermission("overlay.toast", PermissionTier.NORMAL, "Show short messages during games"),
        PluginPermission("notify.post", PermissionTier.NORMAL, "Send you notifications"),
        PluginPermission("net.state", PermissionTier.NORMAL, "See whether you are online"),
        PluginPermission("net.wifi_details", PermissionTier.DANGEROUS, "See the name of your Wi-Fi network"),
        PluginPermission("net.domains", PermissionTier.NORMAL, "Connect to: listed domains"),
        PluginPermission("net.any", PermissionTier.DANGEROUS, "Connect to any site on the internet"),
        PluginPermission("net.local", PermissionTier.DANGEROUS, "Find and connect to devices on your local network"),
        PluginPermission("storage.volumes", PermissionTier.NORMAL, "See your storage devices and free space"),
        PluginPermission("files.picker", PermissionTier.NORMAL, "Ask you to choose files or folders"),
        PluginPermission("files.shared.read", PermissionTier.DANGEROUS, "Read all files on shared storage"),
        PluginPermission("files.shared.write", PermissionTier.DANGEROUS, "Change and delete files on shared storage"),
        PluginPermission("clipboard.write", PermissionTier.NORMAL, "Copy to the clipboard"),
        PluginPermission("clipboard.read", PermissionTier.DANGEROUS, "Read the clipboard"),
        PluginPermission("power.state", PermissionTier.NORMAL, "See battery and charging state"),
        PluginPermission("power.keep_awake", PermissionTier.NORMAL, "Keep the device awake while it works"),
        PluginPermission("display.info", PermissionTier.NORMAL, "See your displays"),
        PluginPermission("display.control", PermissionTier.DANGEROUS, "Change brightness and display settings"),
        PluginPermission("audio.control", PermissionTier.NORMAL, "Change volume and audio output"),
        PluginPermission("media.sessions", PermissionTier.DANGEROUS, "See and control what other apps are playing"),
        PluginPermission("input.devices", PermissionTier.NORMAL, "See connected controllers and keyboards"),
        PluginPermission("bt.devices", PermissionTier.DANGEROUS, "Find and connect Bluetooth devices"),
        PluginPermission("usb.devices", PermissionTier.DANGEROUS, "Use USB devices you connect"),
        PluginPermission("sensors.read", PermissionTier.NORMAL, "Use motion and light sensors"),
        PluginPermission("vibrate", PermissionTier.NORMAL, "Vibrate"),
        PluginPermission("a11y.bridge", PermissionTier.CRITICAL, "Read the screen and act for you", officialOnly = true),
        PluginPermission("containers.read", PermissionTier.NORMAL, "See your Linux and Windows containers"),
        PluginPermission("containers.manage", PermissionTier.DANGEROUS, "Start, stop and create containers"),
        PluginPermission("containers.exec", PermissionTier.CRITICAL, "Run programs inside your containers, with access to everything in them"),
        PluginPermission("terminal.open", PermissionTier.NORMAL, "Open a terminal for you"),
        PluginPermission("windows.read", PermissionTier.DANGEROUS, "See your open windows and their titles"),
        PluginPermission("windows.control", PermissionTier.DANGEROUS, "Focus, move and close your windows"),
        PluginPermission("print.submit", PermissionTier.NORMAL, "Ask to print"),
        PluginPermission("print.admin", PermissionTier.DANGEROUS, "Add and change printers"),
        PluginPermission("background.service", PermissionTier.DANGEROUS, "Keep running in the background"),
        PluginPermission("schedule.jobs", PermissionTier.NORMAL, "Run scheduled tasks"),
        PluginPermission("apps.check", PermissionTier.NORMAL, "Check whether listed apps are installed"),
        PluginPermission("apps.list", PermissionTier.DANGEROUS, "See all apps installed on this device"),
        PluginPermission("apps.launch", PermissionTier.NORMAL, "Open other apps"),
        PluginPermission("apps.intents.out", PermissionTier.NORMAL, "Send information to listed apps / to any app", scopedTier = PermissionTier.DANGEROUS),
        PluginPermission("intents.in", PermissionTier.DANGEROUS, "Be opened by other apps and links"),
        PluginPermission("apps.install", PermissionTier.DANGEROUS, "Install and update apps (Android asks you each time)"),
        PluginPermission("apps.bind", PermissionTier.DANGEROUS, "Stay connected to listed apps in the background"),
        PluginPermission("vault.own", PermissionTier.NORMAL, "Store its own passwords and keys securely"),
        PluginPermission("auth.oauth", PermissionTier.NORMAL, "Ask you to sign in to a service"),
        PluginPermission("web.session", PermissionTier.DANGEROUS, "Use your signed-in session on listed sites"),
        PluginPermission("github.api", PermissionTier.NORMAL, "Use your GitHub token for GitHub requests (the token stays in droidtop)"),
        PluginPermission("github.token.read", PermissionTier.DANGEROUS, "Read your GitHub token"),
        PluginPermission("diagnostics.read", PermissionTier.DANGEROUS, "Read droidtop's own logs", officialOnly = true),
        PluginPermission("telemetry.send", PermissionTier.DANGEROUS, "Send usage data to listed address"),
        PluginPermission("plugins.export", PermissionTier.NORMAL, "Offer features to other plugins"),
        PluginPermission("plugins.export_privileged", PermissionTier.CRITICAL, "Give other plugins root or system-level access"),
        PluginPermission("host.full_trust", PermissionTier.CRITICAL, "Run with droidtop's full access (not contained)"),
        PluginPermission("priv.shell.adb", PermissionTier.CRITICAL, "Run system commands with ADB-level access (through provider)"),
        PluginPermission("priv.shell.root", PermissionTier.CRITICAL, "Run commands as root (through provider)"),
        PluginPermission("priv.packages", PermissionTier.CRITICAL, "Install, remove and change permissions of apps without asking (through provider)"),
        PluginPermission("priv.settings", PermissionTier.CRITICAL, "Change protected system settings (through provider)"),
        PluginPermission("root.modules", PermissionTier.CRITICAL, "Install and remove root modules (through provider)"),
        PluginPermission("input.remap.apply", PermissionTier.DANGEROUS, "Change your controller mapping (through provider)"),    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): PluginPermission? = byId[id]

    /** The extension point a `provide:<point>` consent item names, or null when [id] is not one. */
    fun providedPoint(id: String): String? = id.removePrefix(PROVIDE_PREFIX).takeIf { id.startsWith(PROVIDE_PREFIX) && it.isNotBlank() }

    /** True when the host knows this permission id, or it is `provide:` of a point the host knows. */
    fun isSupported(id: String): Boolean = find(id) != null || providedPoint(id)?.let { ExtensionPoints.find(it) != null } == true

    /** The plain-language label for [id] (a `provide:` item reads "Provide <point>"), or null when unsupported. */
    fun labelFor(id: String): String? = find(id)?.label ?: providedPoint(id)?.takeIf { ExtensionPoints.find(it) != null }?.let { "Add to droidtop: ${ExtensionPoints.find(it)!!.label}" }

    /**
     * The tier the approval screen shows. A `provide:` item is dangerous
     * when the point is high or critical risk ([PointRisk.needsConsent]),
     * and normal otherwise. Null for an unsupported id.
     */
    fun tierFor(id: String, scopeIsAny: Boolean = false): PermissionTier? {
        find(id)?.let { return if (scopeIsAny && it.scopedTier != null) it.scopedTier else it.tier }
        val point = providedPoint(id)?.let { ExtensionPoints.find(it) } ?: return null
        return when (point.risk) {
            PointRisk.CRITICAL -> PermissionTier.CRITICAL
            PointRisk.HIGH -> PermissionTier.DANGEROUS
            else -> PermissionTier.NORMAL
        }
    }
}
