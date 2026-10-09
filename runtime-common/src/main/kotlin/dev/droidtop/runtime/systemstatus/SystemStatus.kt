package dev.droidtop.runtime.systemstatus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * One shared system-status source for every droidtop surface that shows
 * it — the Companion bar, the Desktop taskbar tray, and the Gaming
 * settings' System group all read THIS, rather than each mode growing
 * its own battery receiver and network callback (docs/SPEC.md's own
 * settings philosophy: shared data, per-surface chrome).
 *
 * The ES-DE theme renderer keeps its own tiny readout deliberately: its
 * statusbar binding is part of faithfully rendering a THEME's elements,
 * versioned with the theme schema, not a droidtop surface.
 *
 * SSID is deliberately not part of the snapshot: reading it on modern
 * Android requires fine-location permission and live location toggles,
 * which is a heavy, scary grant for a status readout. Connectivity kind
 * plus signal level answers "is my Wi-Fi ok" without it.
 */
data class SystemStatusSnapshot(
    val batteryPercent: Int?,
    val charging: Boolean,
    val network: NetworkKind,
    /** 0..4 when on Wi-Fi and readable; null otherwise. */
    val wifiLevel: Int?,
    /**
     * Whether the connection actually reaches the internet
     * (NET_CAPABILITY_VALIDATED) -- "connected to Wi-Fi" and "has
     * internet" are different facts, and the gap between them is the
     * captive portal every travelling handheld meets. False while
     * connected means exactly "signed-in Wi-Fi without internet".
     */
    val validated: Boolean,
    /** A VPN is active on the current network (TRANSPORT_VPN). */
    val vpnActive: Boolean,
)

enum class NetworkKind { WIFI, ETHERNET, CELLULAR, NONE }

object SystemStatus {

    fun snapshot(context: Context): SystemStatusSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val kind = when {
            caps == null -> NetworkKind.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
            else -> NetworkKind.NONE
        }

        val wifiLevel = if (kind == NetworkKind.WIFI) {
            runCatching {
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val rssi = wm?.connectionInfo?.rssi
                if (wm != null && rssi != null && rssi != -127) {
                    WifiManager.calculateSignalLevel(rssi, 5)
                } else null
            }.getOrNull()
        } else null

        return SystemStatusSnapshot(percent, charging, kind, wifiLevel, validated, vpnActive)
    }

    /**
     * Live snapshots: re-emits on every battery tick and connectivity
     * change. The battery receiver is the real update clock here —
     * ACTION_BATTERY_CHANGED fires on percent/plug changes, which is
     * exactly the cadence a status readout wants.
     */
    fun flow(context: Context): Flow<SystemStatusSnapshot> = callbackFlow {
        val appContext = context.applicationContext
        trySend(snapshot(appContext))

        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                trySend(snapshot(appContext))
            }
        }
        appContext.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(snapshot(appContext)) }
            override fun onLost(network: Network) { trySend(snapshot(appContext)) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(snapshot(appContext))
            }
        }
        cm?.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)

        awaitClose {
            runCatching { appContext.unregisterReceiver(batteryReceiver) }
            runCatching { cm?.unregisterNetworkCallback(networkCallback) }
        }
    }
}

/**
 * The system controls the status surfaces expose, kept honest about
 * what a non-system app can actually do on modern Android:
 *
 * - Volume: fully controllable ([AudioManager], no permission).
 * - Brightness: writable only with the WRITE_SETTINGS special-access
 *   grant, which the user gives on a system screen droidtop can OPEN
 *   but not skip — so the API is get/set plus an explicit
 *   "can I?"/"take me to the grant" pair, never a silent failure.
 * - Wi-Fi: programmatic toggling was removed from app reach in API 29.
 *   The real mechanism is the system's own internet panel
 *   ([Settings.Panel.ACTION_INTERNET_CONNECTIVITY]) — the same sheet
 *   the quick-settings tile opens, usable from any app. Pretending to
 *   toggle and failing would be worse than opening the real control.
 */
object SystemControls {

    fun volumeRange(context: Context): IntRange {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return 0..am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    fun volume(context: Context): Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getStreamVolume(AudioManager.STREAM_MUSIC)

    fun setVolume(context: Context, value: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            value.coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
            0,
        )
    }

    /** Brightness is [dev.droidtop.runtime.DisplayControls]'s, the one write path per display (slice C14). */
    fun canWriteBrightness(context: Context): Boolean = dev.droidtop.runtime.DisplayControls.canWriteBrightness(context)

    fun brightnessGrantIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun internetPanelIntent(): Intent =
        Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun bluetoothSettingsIntent(): Intent =
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Bluetooth's radio as the system holds it (the global setting, readable with no permission); never written. */
    fun bluetoothOn(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON) == 1
    }.getOrDefault(false)

    /** Airplane mode as the system holds it; an app can read it, never write it. */
    fun airplaneModeOn(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON) == 1
    }.getOrDefault(false)

    fun airplaneModeSettingsIntent(): Intent =
        Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun locationOn(context: Context): Boolean = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (android.os.Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)

    fun locationSettingsIntent(): Intent =
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Microphone mute for every app (AudioManager; MODIFY_AUDIO_SETTINGS, a normal permission). */
    fun micMuted(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMicrophoneMute
    }.getOrDefault(false)

    fun setMicMuted(context: Context, muted: Boolean) {
        runCatching { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMicrophoneMute = muted }
    }

    /** The first camera with a flash unit, which the torch switches; null on a device without one. */
    private fun torchCamera(context: Context): String? = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    fun hasFlashlight(context: Context): Boolean = torchCamera(context) != null

    @Volatile
    private var torch = false

    /** The torch as droidtop last set it (Android reports changes only to a registered callback). */
    fun flashlightOn(): Boolean = torch

    fun setFlashlight(context: Context, on: Boolean) {
        val camera = torchCamera(context) ?: return
        runCatching {
            (context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager).setTorchMode(camera, on)
            torch = on
        }
    }

    /** The output switcher panel (API 29), else the Sound screen it grew out of. */
    fun audioOutputIntent(): Intent =
        Intent(
            if (android.os.Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The system's own Battery Saver screen (ACTION_BATTERY_SAVER_SETTINGS, API 22). */
    fun batterySaverSettingsIntent(): Intent =
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Whether the system's Battery Saver is on (PowerManager.isPowerSaveMode, no permission). */
    fun powerSaveOn(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode == true

    /** The battery tile's value: "83%", "83%, charging", plus ", saver on". Pure so it is testable. */
    fun batteryValue(percent: Int?, charging: Boolean, saverOn: Boolean): String {
        val level = percent?.let { "$it%" } ?: "Unknown"
        return level + (if (charging) ", charging" else "") + (if (saverOn) ", saver on" else "")
    }

    fun allSettingsIntent(): Intent =
        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------
    // Do Not Disturb -- genuinely OWNABLE after a one-time grant
    // (notification-policy access, given on a system screen droidtop
    // opens). One of the few real controls Android still lets an
    // ordinary app hold, so droidtop holds it.
    // ------------------------------------------------------------------

    fun hasDndAccess(context: Context): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .isNotificationPolicyAccessGranted

    fun dndEnabled(context: Context): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL

    /** False when the policy-access grant is missing -- surface the grant action instead. */
    fun setDnd(context: Context, enabled: Boolean): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return false
        return runCatching {
            nm.setInterruptionFilter(
                if (enabled) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL,
            )
        }.isSuccess
    }

    fun dndGrantIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------
    // Settings.System-backed controls -- all behind the same
    // WRITE_SETTINGS grant brightness already uses, so once the user has
    // granted it for one of these they have granted it for all.
    // ------------------------------------------------------------------

    fun screenTimeoutMs(context: Context): Int? = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
    }.getOrNull()

    fun setScreenTimeoutMs(context: Context, ms: Int): Boolean {
        if (!Settings.System.canWrite(context)) return false
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms.coerceAtLeast(5_000))
        }.isSuccess
    }

    fun autoRotate(context: Context): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1
    }.getOrDefault(false)

    fun setAutoRotate(context: Context, enabled: Boolean): Boolean {
        if (!Settings.System.canWrite(context)) return false
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (enabled) 1 else 0,
            )
        }.isSuccess
    }

    fun adaptiveBrightness(context: Context): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    fun setAdaptiveBrightness(context: Context, enabled: Boolean): Boolean {
        if (!Settings.System.canWrite(context)) return false
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }.isSuccess
    }

    /** The screen-timeout choices Android's own Settings offers, labelled the same way; one list for every surface. */
    val SCREEN_TIMEOUTS = listOf(
        15_000 to "15 seconds",
        30_000 to "30 seconds",
        60_000 to "1 minute",
        120_000 to "2 minutes",
        300_000 to "5 minutes",
        600_000 to "10 minutes",
        1_800_000 to "30 minutes",
    )

    /**
     * A screen timeout in words, for any value Android holds, not only the ones [SCREEN_TIMEOUTS] offers: the
     * largest int is Android's "never" (a rig showed "2147483 seconds", build 1702), others read in the largest
     * whole unit that fits.
     */
    fun timeoutLabel(ms: Int): String {
        SCREEN_TIMEOUTS.firstOrNull { it.first == ms }?.let { return it.second }
        if (ms == Int.MAX_VALUE || ms < 0) return "Never"
        val seconds = ms / 1000
        fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
        return when {
            seconds >= 3600 && seconds % 3600 == 0 -> plural(seconds / 3600, "hour")
            seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
            seconds >= 60 && seconds % 60 == 0 -> plural(seconds / 60, "minute")
            seconds >= 60 -> "${seconds / 60} min ${seconds % 60} s"
            else -> plural(seconds, "second")
        }
    }

    // ------------------------------------------------------------------
    // Radios. A normal app cannot switch Wi-Fi, Bluetooth or airplane mode
    // (removed from app reach in API 29 and later), so the only honest
    // routes are the system's own panel, or a privileged provider running
    // the same command Android's shell does. Which one is the caller's
    // call; this only names both.
    // ------------------------------------------------------------------

    enum class Radio(val label: String) { WIFI("Wi-Fi"), BLUETOOTH("Bluetooth"), AIRPLANE("Airplane mode") }

    /** The shell command that switches [radio], for a `priv.shell` provider. */
    fun radioCommand(radio: Radio, on: Boolean): List<String> {
        val verb = if (on) "enable" else "disable"
        return when (radio) {
            Radio.WIFI -> listOf("svc", "wifi", verb)
            Radio.BLUETOOTH -> listOf("svc", "bluetooth", verb)
            Radio.AIRPLANE -> listOf("cmd", "connectivity", "airplane-mode", verb)
        }
    }

    /** Whether [radio] is on; null when the platform does not say. No permission beyond what droidtop holds. */
    fun radioOn(context: Context, radio: Radio): Boolean? = runCatching {
        when (radio) {
            Radio.WIFI -> (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
            Radio.BLUETOOTH ->
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter?.isEnabled
            Radio.AIRPLANE -> Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // Direct links into the system screens the platform refuses to let
    // an app own. Filtered to what actually RESOLVES on this device --
    // an OEM build missing a screen must not produce a dead row.
    // ------------------------------------------------------------------

    data class SettingsLink(val id: String, val label: String, val intent: Intent)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun settingsLinks(context: Context): List<SettingsLink> {
        val candidates = listOf(
            SettingsLink("display", "Display", Intent(Settings.ACTION_DISPLAY_SETTINGS)),
            SettingsLink("sound", "Sound & vibration", Intent(Settings.ACTION_SOUND_SETTINGS)),
            SettingsLink("battery", "Battery saver", Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)),
            SettingsLink("storage", "Storage", Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
            SettingsLink("apps", "Apps", Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)),
            SettingsLink("notifications", "Notifications", Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS)),
            SettingsLink("datetime", "Date & time", Intent(Settings.ACTION_DATE_SETTINGS)),
            SettingsLink("locale", "Languages", Intent(Settings.ACTION_LOCALE_SETTINGS)),
            SettingsLink("keyboard", "Keyboards & input", Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)),
            SettingsLink("vpn", "VPN", Intent(Settings.ACTION_VPN_SETTINGS)),
            SettingsLink("airplane", "Airplane mode", Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)),
            SettingsLink("cast", "Cast", Intent(Settings.ACTION_CAST_SETTINGS)),
            SettingsLink("nfc", "NFC", Intent(Settings.ACTION_NFC_SETTINGS)),
            // Printing is a PC-parity requirement (SPEC 4b) -- the system
            // print-services screen is where CUPS-backed services land.
            SettingsLink("print", "Printing", Intent(Settings.ACTION_PRINT_SETTINGS)),
            SettingsLink("accessibility", "Accessibility", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            SettingsLink("security", "Security", Intent(Settings.ACTION_SECURITY_SETTINGS)),
            SettingsLink("privacy", "Privacy", Intent(Settings.ACTION_PRIVACY_SETTINGS)),
            SettingsLink("location", "Location", Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
            SettingsLink("developer", "Developer options", Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),
            SettingsLink("about", "About this device", Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)),
        )
        val pm = context.packageManager
        return candidates
            .map { it.copy(intent = it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .filter { it.intent.resolveActivity(pm) != null }
    }
}
