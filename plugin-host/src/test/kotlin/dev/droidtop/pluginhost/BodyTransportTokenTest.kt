package dev.droidtop.pluginhost

import android.os.IBinder
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BodyTransportTokenTest {
    @Before fun deliverImmediately() { WebViewProviders.deliver = { it() } }

    private fun transport(): BrowserBodyTransport = object : BrowserBodyTransport {
        override val binder = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "test body transport"
                "isBinderAlive", "pingBinder" -> true
                else -> null
            }
        } as IBinder
        override val runtime: IBinder get() = binder
        override fun download(token: String, offerId: String) = throw AssertionError("No body was offered")
        override fun cancel(token: String, offerId: String) = Unit
        override fun acknowledge(token: String, offerId: String, accepted: Boolean) = Unit
    }

    private fun token(closed: () -> Unit = {}): String = WebViewProviders.register("browser", WebSessionRequest("Site", WebSessionRequest.Mode.OPEN, "https://example.com/", listOf("example.com"), null, null), emptyMap()) { closed() }

    @Test fun `body authority must be negotiated before the one-use request is taken`() {
        val token = token()
        WebViewProviders.requireBodyBeforeTake(token)
        assertTrue(runCatching { WebViewProviders.take("browser", token) }.isFailure)
        assertTrue(WebViewProviders.bodyReady(token, transport()))
        WebViewProviders.take("browser", token)
        assertFalse(WebViewProviders.bodyReady(token, transport()))
        WebViewProviders.cancel(token)
        assertFalse(WebViewProviders.bodyReady(token, transport()))
    }

    @Test fun `withdraw and finish release the pending provider binding exactly once`() {
        for (take in listOf(false, true)) {
            val released = AtomicInteger()
            val closed = AtomicInteger()
            val token = token { closed.incrementAndGet() }
            assertTrue(WebViewProviders.keepBodyProvider(token) { released.incrementAndGet() })
            if (take) {
                WebViewProviders.take("browser", token)
                WebViewProviders.finish(null, "browser", token, JSONObject().put("completed", false))
                assertEquals(1, closed.get())
            } else {
                assertTrue(WebViewProviders.withdrawUntaken(token))
                assertEquals(0, closed.get())
            }
            WebViewProviders.cancel(token)
            assertFalse(WebViewProviders.withdrawUntaken(token))
            assertEquals(1, released.get())
        }
    }

    @Test fun `simultaneous UI and provider-death cleanup closes one lease once`() {
        val runtime = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IPluginRuntime::class.java)) { _, _, _ -> null } as IPluginRuntime
        val releases = AtomicInteger()
        val lease = AppInterfaceConnections.Lease(runtime) { releases.incrementAndGet() }
        val cleanupGate = java.util.concurrent.CountDownLatch(1)
        val threads = List(16) { Thread { cleanupGate.await(); lease.close() }.apply { start() } }
        cleanupGate.countDown()
        threads.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertEquals(1, releases.get())
    }
}
