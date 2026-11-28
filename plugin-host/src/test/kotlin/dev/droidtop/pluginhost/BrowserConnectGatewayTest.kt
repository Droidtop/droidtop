package dev.droidtop.pluginhost

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BrowserConnectGatewayTest {
    private fun request(socket: Socket, authorization: String): String {
        socket.soTimeout = 3000
        socket.getOutputStream().write(("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n" +
            "User-Agent: Gecko\r\nProxy-Connection: keep-alive\r\nConnection: keep-alive\r\n" +
            "Proxy-Authorization: $authorization\r\n\r\n").toByteArray())
        val bytes = java.io.ByteArrayOutputStream()
        var tail = 0
        while (bytes.size() < 8192) {
            val next = socket.getInputStream().read()
            check(next >= 0)
            bytes.write(next)
            tail = (tail shl 8) or next
            if (tail == 0x0d0a0d0a) break
        }
        return bytes.toString("US-ASCII")
    }

    @Test fun wrongSecretDoesNotResolveOrAuthorize() {
        val calls = AtomicInteger()
        BrowserConnectGateway.open(5000, beforeResolve = { calls.incrementAndGet() },
            authorize = { _, _ -> calls.incrementAndGet() }, resolve = { calls.incrementAndGet(); emptyList() }).use { gateway ->
            Socket(gateway.endpoint.host, gateway.endpoint.port).use { client ->
                assertTrue(request(client, "Bearer wrong").startsWith("HTTP/1.1 407"))
            }
            assertEquals(0, calls.get())
            assertFalse(gateway.endpoint.toString().contains(gateway.endpoint.authorization))
        }
    }

    @Test fun deniedNameNeverResolves() {
        val resolutions = AtomicInteger()
        BrowserConnectGateway.open(5000, beforeResolve = { throw SecurityException() },
            authorize = { _, _ -> fail("Address policy must not run") },
            resolve = { resolutions.incrementAndGet(); emptyList() }).use { gateway ->
            Socket(gateway.endpoint.host, gateway.endpoint.port).use { client ->
                assertTrue(request(client, gateway.endpoint.authorization).startsWith("HTTP/1.1 403"))
            }
            assertEquals(0, resolutions.get())
        }
    }

    @Test fun sameAuthorizedAddressConnectsOnceAndProxyHeadersDoNotEnterTunnel() {
        val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val resolutions = AtomicInteger()
        ServerSocket(0, 1, address).use { origin ->
            BrowserConnectGateway.open(5000, beforeResolve = { assertEquals("https://example.com:443/", it) },
                authorize = { _, addresses -> assertSame(address, addresses.single()) },
                resolve = { resolutions.incrementAndGet(); listOf(address) },
                connect = { socket, selected, _, timeout ->
                    assertSame(address, selected)
                    socket.connect(InetSocketAddress(selected, origin.localPort), timeout)
                }).use { gateway ->
                Socket(gateway.endpoint.host, gateway.endpoint.port).use { client ->
                    assertTrue(request(client, gateway.endpoint.authorization).startsWith("HTTP/1.1 200"))
                    origin.soTimeout = 3000
                    origin.accept().use { peer ->
                        peer.soTimeout = 3000
                        client.getOutputStream().write(42)
                        assertEquals(42, peer.getInputStream().read())
                        peer.getOutputStream().write(43)
                        assertEquals(43, client.getInputStream().read())
                        gateway.close()
                        assertEquals(-1, peer.getInputStream().read())
                    }
                }
                assertEquals(1, resolutions.get())
            }
        }
    }

    @Test fun deadlineClosesIdleUnauthenticatedClient() {
        BrowserConnectGateway.open(150, beforeResolve = { fail("No request was supplied") },
            authorize = { _, _ -> fail("No request was supplied") }).use { gateway ->
            Socket(gateway.endpoint.host, gateway.endpoint.port).use { client ->
                client.soTimeout = 3000
                assertEquals(-1, client.getInputStream().read())
                assertEquals(BrowserConnectGateway.Failure.DEADLINE, gateway.failureReason)
            }
        }
    }

    @Test fun deniedResolvedAddressNeverConnects() {
        val connections = AtomicInteger()
        BrowserConnectGateway.open(5000, beforeResolve = {}, authorize = { _, _ -> throw SecurityException() },
            resolve = { listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))) },
            connect = { _, _, _, _ -> connections.incrementAndGet() }).use { gateway ->
            Socket(gateway.endpoint.host, gateway.endpoint.port).use { client ->
                assertTrue(request(client, gateway.endpoint.authorization).startsWith("HTTP/1.1 403"))
            }
            assertEquals(0, connections.get())
        }
    }
}
