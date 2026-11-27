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

internal class DownloadBodyTransport(private val provider: IWebDownloadProvider) : BrowserBodyTransport {
    override val binder: IBinder get() = provider.asBinder()
    override fun download(token: String, offerId: String) = provider.download(token, offerId)
    override fun cancel(token: String, offerId: String) = provider.cancelDownload(token, offerId)
    override fun acknowledge(token: String, offerId: String, accepted: Boolean) = provider.offerResult(token, offerId, accepted)
}
