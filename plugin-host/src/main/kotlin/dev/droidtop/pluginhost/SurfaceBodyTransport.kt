package dev.droidtop.pluginhost

import android.annotation.TargetApi
import android.os.IBinder

/** Loaded only by the API 30+ embedded surface path. */
@TargetApi(30)
internal class SurfaceBodyTransport(private val provider: IWebViewSurfaceProvider) : BrowserBodyTransport {
    override val binder: IBinder get() = provider.asBinder()
    override fun download(token: String, offerId: String) = provider.download(token, offerId)
    override fun cancel(token: String, offerId: String) = provider.cancelDownload(token, offerId)
    override fun acknowledge(token: String, offerId: String, accepted: Boolean) = provider.offerResult(token, offerId, accepted)
}
