package dev.droidtop.runtime.systemstatus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import java.util.concurrent.ConcurrentHashMap

/**
 * What the notification model needs to know about an app, looked up once per package and cached for the process
 * (Droidtop/tracker#786 S13a: "App labels and icons are cached per package off the main thread"): its label, its icon
 * as a small bitmap, and whether it provides a VPN. The listener used to ask the package manager for the label of
 * every notification on every post, inside its callback. Blocks: called only from the model's background thread.
 */
object NotificationApps {
    data class App(val label: String, val icon: Bitmap?, val vpnProvider: Boolean)

    private val cache = ConcurrentHashMap<String, App>()

    /** The icon's edge in pixels: drawn at 24 dp, so 72 px covers a 3x screen. */
    private const val ICON_PX = 72

    fun of(context: Context, packageName: String): App = cache.getOrPut(packageName) { load(context, packageName) }

    private fun load(context: Context, packageName: String): App {
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
        val label = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: packageName
        val icon = info?.let { runCatching { bitmapOf(pm.getApplicationIcon(it)) }.getOrNull() }
        return App(label, icon, providesVpn(pm, packageName))
    }

    /** Whether the app declares a VPN service (one bound with BIND_VPN_SERVICE), as every VPN app must. */
    private fun providesVpn(pm: PackageManager, packageName: String): Boolean = runCatching {
        pm.queryIntentServices(Intent("android.net.VpnService").setPackage(packageName), 0).any {
            it.serviceInfo?.permission == android.Manifest.permission.BIND_VPN_SERVICE
        }
    }.getOrDefault(false)

    private fun bitmapOf(drawable: android.graphics.drawable.Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(canvas)
        return bitmap
    }
}
