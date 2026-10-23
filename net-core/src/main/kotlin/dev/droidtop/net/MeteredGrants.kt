package dev.droidtop.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The person's leave to download on a metered connection (docs/SPEC.md 12a, "A metered connection is allowed, never a
 * job"). The owner, 2026-10-23: the person "can't enable A SINGLE download. The user can disable the metered flag, or
 * they can allow downloads while connected to this metered connection once, or always". So a grant names a
 * connection, never a job, and every download on that connection goes.
 *
 * - **Once** lasts while the connection does: it is the connection's Android network handle, which a reconnect replaces.
 * - **Always** is kept by the connection's [Connection.key]: the SIM for mobile data, which needs no permission, or the
 *   Wi-Fi network's name, which Android gives only to an app holding the location permission. That permission is
 *   offered for this alone and is not recommended; Android's own "metered" switch for the Wi-Fi network is the
 *   recommended way, and droidtop never changes it.
 *
 * Grants are read through [allows]; the always list is kept in preferences, read once with [load] off the main thread.
 */
object MeteredGrants {
    private const val PREFS = "metered_grants"

    enum class Kind { WIFI, MOBILE, OTHER }

    /**
     * The connection downloads would use now. [handle] tells one connection from the next; [key] names it across
     * connections ("sim:3", "wifi:Home"), null when Android does not say which it is; [label] is what the person reads.
     */
    data class Connection(val handle: Long, val kind: Kind, val key: String?, val label: String)

    private val onceHandle = MutableStateFlow<Long?>(null)
    private val alwaysFlow = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The connections allowed always, key to label, for Download rules to list and take back. */
    val always: StateFlow<Map<String, String>> = alwaysFlow

    /** The connection allowed once, by its handle; null when none is. */
    val once: StateFlow<Long?> = onceHandle

    /** Whether downloads may use [connection] although it is metered. Pure. */
    fun allows(connection: Connection?, onceHandle: Long?, always: Map<String, String>): Boolean =
        connection != null && (connection.handle == onceHandle || (connection.key != null && connection.key in always))

    fun allows(connection: Connection?): Boolean = allows(connection, onceHandle.value, alwaysFlow.value)

    /** Allows [connection] until it disconnects. */
    fun allowOnce(connection: Connection) {
        onceHandle.value = connection.handle
    }

    /** Allows [connection] from now on; false when Android does not say which connection it is (no [Connection.key]). */
    fun allowAlways(context: Context, connection: Connection): Boolean {
        val key = connection.key ?: return false
        alwaysFlow.value = alwaysFlow.value + (key to connection.label)
        prefs(context).edit().putString(key, connection.label).apply()
        return true
    }

    /** Takes back the always grant for [key]. */
    fun remove(context: Context, key: String) {
        alwaysFlow.value = alwaysFlow.value - key
        prefs(context).edit().remove(key).apply()
    }

    /** Reads the always list. Touches the preferences file: not for the main thread. */
    fun load(context: Context) {
        alwaysFlow.value = prefs(context).all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
    }

    /** Whether droidtop may read the Wi-Fi network's name, so "always" can be offered on a Wi-Fi network. */
    fun canNameWifi(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The connection downloads would use now, or null when there is none. Cheap system calls; safe off the main thread. */
    fun connectionNow(context: Context): Connection? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val handle = network.networkHandle
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val name = wifiName(context)
                Connection(handle, Kind.WIFI, name?.let { "wifi:$it" }, name ?: "This Wi-Fi network")
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val sub = SubscriptionManager.getDefaultDataSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
                val carrier = sub?.let { id ->
                    runCatching {
                        context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(id)?.simOperatorName
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                }
                Connection(handle, Kind.MOBILE, sub?.let { "sim:$it" }, carrier?.let { "Mobile data ($it)" } ?: "Mobile data")
            }
            else -> Connection(handle, Kind.OTHER, null, "This connection")
        }
    }

    /** The Wi-Fi network's name, or null without the location permission (Android then hides it). */
    @Suppress("DEPRECATION")
    private fun wifiName(context: Context): String? {
        if (!canNameWifi(context)) return null
        val ssid = runCatching { context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull()
        return ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
