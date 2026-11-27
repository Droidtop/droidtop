package dev.droidtop.pluginhost

import android.os.IBinder
import android.os.ParcelFileDescriptor

/** Response ownership independent of any embedded view or Android 30 surface type. */
internal interface BrowserBodyTransport {
    val binder: IBinder
    fun download(token: String, offerId: String): ParcelFileDescriptor?
    fun cancel(token: String, offerId: String)
    fun acknowledge(token: String, offerId: String, accepted: Boolean)
}
