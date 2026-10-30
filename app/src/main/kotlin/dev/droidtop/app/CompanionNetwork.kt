package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.display.secondScreenScroll
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

/**
 * The companion's Network tab (docs/SPEC.md "The companion's tabs", Network; Droidtop/tracker#583; owner, 2026-10-17:
 * "put that in its own network tab. Move wifi, cellular, ethernet, virtual interface management, and etc there too,
 * though they should display contextually, with a manage button"). Each network that is up is one row, only what
 * this device has: what it is, its state and name, and Manage, which opens Android's own screen for it. A VPN reads
 * "State (name)" with Details; under them every network interface the device has. Read off the main thread when the
 * tab opens and when Refresh is pressed.
 */
@Composable
internal fun CompanionNetworkTab() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val state by produceState<NetworkState?>(null, version) {
        value = withContext(Dispatchers.IO) { readNetworks(context.applicationContext) }
    }
    Column(
        modifier = Modifier.fillMaxSize().secondScreenScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val now = state
        if (now == null) {
            CompanionNote("Reading…")
            return@Column
        }
        if (now.networks.isEmpty()) {
            CompanionCard("Offline") {
                CompanionNote("No network is connected")
                CompanionPill("Manage") { SettingsLaunch.start(context, manageIntent(NetKind.WIFI)) }
            }
        }
        now.networks.forEach { net -> NetworkCard(net, now.helper) }
        CompanionCard("Interfaces") {
            if (now.interfaces.isEmpty()) CompanionNote("None up")
            now.interfaces.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            // Read with the helper app only (it was System > Connections' line before the Network tab).
            now.adbWifi?.let { CompanionNote(if (it) "Wireless debugging: on" else "Wireless debugging: off") }
        }
        CompanionPill("Refresh") { version++ }
    }
}

@Composable
private fun NetworkCard(net: NetFact, helper: Boolean) {
    val context = LocalContext.current
    var details by remember(net.kind, net.iface) { mutableStateOf(false) }
    CompanionCard(net.kind.label) {
        Text(netLine(net), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        detailFacts(net).takeIf { details }?.forEach { CompanionNote(it) }
        if (details && net.kind == NetKind.VPN) {
            if (net.name == null) CompanionNote(vpnNameMissing(helper))
            PublicAddress()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CompanionPill(if (details) "Hide details" else "Details") { details = !details }
            CompanionPill("Manage") { SettingsLaunch.start(context, manageIntent(net.kind)) }
        }
    }
}

/**
 * Where this device's traffic leaves for the internet: asked of an outside service only when the person presses the
 * button (owner, 2026-10-17: "yes, behind a button in Details"), never by itself.
 */
@Composable
private fun PublicAddress() {
    var answer by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    answer?.let { CompanionNote(it) }
    CompanionPill("Check public address") {
        answer = "Asking ${PUBLIC_ADDRESS_HOST}…"
        scope.launch {
            answer = withContext(Dispatchers.IO) {
                runCatching {
                    val reply = dev.droidtop.net.Http.get(PUBLIC_ADDRESS_URL, maxBytes = 256).text().trim()
                    "Public address: $reply (answered by $PUBLIC_ADDRESS_HOST)"
                }.getOrElse { "Could not ask $PUBLIC_ADDRESS_HOST: ${it.message ?: "no answer"}" }
            }
        }
    }
}

private const val PUBLIC_ADDRESS_HOST = "api.ipify.org"
private const val PUBLIC_ADDRESS_URL = "https://api.ipify.org"

/** What kind of network one is, by its transport, in the order the tab lists them. */
internal enum class NetKind(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Mobile data"),
    ETHERNET("Ethernet"),
    VPN("VPN"),
    BLUETOOTH("Bluetooth tethering"),
    USB("USB"),
    OTHER("Network"),
}

/** One network that is up, as the tab shows it. */
internal data class NetFact(
    val kind: NetKind,
    val name: String?,
    val iface: String?,
    val addresses: List<String>,
    val dns: List<String>,
    val allTraffic: Boolean,
    val metered: Boolean,
    val signalLevel: Int? = null,
    val bandGhz: String? = null,
    val linkMbps: Int? = null,
)

internal data class NetworkState(val networks: List<NetFact>, val interfaces: List<String>, val helper: Boolean, val adbWifi: Boolean? = null)

/** The row's one line: "Connected (Home)", a VPN's "Connected (WireGuard)", or "Connected" with no name. Pure. */
internal fun netLine(net: NetFact): String = "Connected" + (net.name?.let { " ($it)" } ?: "")

/** What Details adds under the line. Pure. */
internal fun detailFacts(net: NetFact): List<String> = buildList {
    net.iface?.let { add("Interface $it") }
    if (net.addresses.isNotEmpty()) add("Address " + net.addresses.joinToString(", "))
    if (net.dns.isNotEmpty()) add("DNS " + net.dns.joinToString(", "))
    listOfNotNull(
        net.signalLevel?.let { "Signal $it of 4" },
        net.bandGhz,
        net.linkMbps?.let { "$it Mbps" },
    ).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
    if (net.kind == NetKind.VPN) add(if (net.allTraffic) "All traffic goes through it" else "Only some traffic goes through it")
    add(if (net.metered) "Metered" else "Not metered")
}

/** Why a VPN has no name: Android does not say which app provides it to other apps. Pure. */
internal fun vpnNameMissing(helper: Boolean): String = if (helper) {
    "Android names the VPN's app only when it is the always-on VPN"
} else {
    "Android does not tell other apps which app provides the VPN"
}

/** The transport that decides a network's kind, a VPN first since it rides on another. Pure. */
internal fun kindOf(has: (Int) -> Boolean): NetKind = when {
    has(NetworkCapabilities.TRANSPORT_VPN) -> NetKind.VPN
    has(NetworkCapabilities.TRANSPORT_WIFI) -> NetKind.WIFI
    has(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.CELLULAR
    has(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.ETHERNET
    has(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetKind.BLUETOOTH
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && has(NetworkCapabilities.TRANSPORT_USB) -> NetKind.USB
    else -> NetKind.OTHER
}

/** "5 GHz" or "2.4 GHz" (or "6 GHz") from a Wi-Fi frequency in MHz. Pure. */
internal fun band(frequencyMhz: Int): String? = when (frequencyMhz) {
    in 2400..2500 -> "2.4 GHz"
    in 4900..5900 -> "5 GHz"
    in 5925..7125 -> "6 GHz"
    else -> null
}

/** Android's own screen for managing [kind]: its panel where there is one (Android 10 and later). */
private fun manageIntent(kind: NetKind): Intent = when (kind) {
    NetKind.WIFI -> Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
    NetKind.VPN -> Intent(Settings.ACTION_VPN_SETTINGS)
    NetKind.CELLULAR -> Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS)
    else -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
}.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun routesAll(link: LinkProperties?): Boolean =
    link?.routes?.any { it.isDefaultRoute } == true

@Suppress("DEPRECATION")
private fun readNetworks(context: Context): NetworkState {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val shell = TaskManager.shell
    val helper = runCatching { shell.capabilities().shellCommand }.getOrDefault(false)
    val networks = cm?.allNetworks.orEmpty().mapNotNull { network ->
        val caps = cm?.getNetworkCapabilities(network) ?: return@mapNotNull null
        val link = cm.getLinkProperties(network)
        val kind = kindOf(caps::hasTransport)
        val wifi = if (kind == NetKind.WIFI) context.getSystemService(WifiManager::class.java)?.connectionInfo else null
        val name = when (kind) {
            // The network's name: Android shows it to apps only with location; the helper app reads `cmd wifi status`.
            NetKind.WIFI -> if (helper) shellLine(listOf("cmd", "wifi", "status"))?.let { Regex("SSID: \"([^\"]+)\"").find(it)?.groupValues?.get(1) } else null
            // Which app provides the VPN, where Android says: the always-on VPN setting, read with the helper app.
            NetKind.VPN -> if (helper) shellLine(listOf("settings", "get", "secure", "always_on_vpn_app"))?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" }?.let { pkg -> appLabel(context, pkg) ?: pkg } else null
            else -> null
        }
        NetFact(
            kind = kind,
            name = name,
            iface = link?.interfaceName,
            addresses = link?.linkAddresses?.map { it.address.hostAddress.orEmpty() }?.filter { it.isNotBlank() && !it.startsWith("fe80") }.orEmpty(),
            dns = link?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            allTraffic = routesAll(link),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            signalLevel = wifi?.let { WifiManager.calculateSignalLevel(it.rssi, 5) },
            bandGhz = wifi?.frequency?.let(::band),
            linkMbps = wifi?.linkSpeed?.takeIf { it > 0 },
        )
    }.sortedBy { it.kind.ordinal }
    val interfaces = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .map { nif ->
                val addresses = nif.inetAddresses.toList().mapNotNull { it.hostAddress }.filter { !it.startsWith("fe80") }
                listOf(nif.name, addresses.joinToString(", ").ifEmpty { "no address" }).joinToString(": ")
            }
    }.getOrDefault(emptyList())
    val adbWifi = if (helper) shellLine(listOf("settings", "get", "global", "adb_wifi_enabled"))?.trim()?.let { it == "1" } else null
    return NetworkState(networks, interfaces, helper, adbWifi)
}

private fun shellLine(argv: List<String>): String? = runCatching { TaskManager.shell.exec(argv)?.stdout }.getOrNull()

private fun appLabel(context: Context, pkg: String): String? = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrNull()
