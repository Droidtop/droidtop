package dev.droidtop.net.kdeconnect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** lib-kdeconnect's replies as droidtop reads them (docs/SPEC.md "KDE Connect"); the shapes are kdeconnect-service's. */
class KdeConnectTest {
    @Test
    fun devices() {
        val reply = JSONObject(
            """{"devices":[{"id":"abc","name":"Pixel","type":"phone","reachable":true,"paired":false,"pairing":"requested_by_peer",
               "verification_key":"54DC916E","address":"192.168.1.9","incoming":[],"outgoing":[]},
               {"id":"def","name":"PC","type":"desktop","reachable":false,"paired":true,"pairing":"none","verification_key":null,
               "address":null,"incoming":[],"outgoing":[]}]}""",
        )
        val d = KdcDevice.parseList(reply)
        assertEquals(2, d.size)
        assertEquals("requested_by_peer", d[0].pairing)
        assertEquals("54DC916E", d[0].verificationKey)
        assertEquals("192.168.1.9", d[0].address)
        assertTrue(d[1].paired)
        assertNull(d[1].verificationKey)
        assertNull(d[1].address)
        assertEquals(emptyList<KdcDevice>(), KdcDevice.parseList(JSONObject("""{"error":"KDE Connect is not on"}""")))
    }

    @Test
    fun events() {
        assertNull(KdcEvent.parse(JSONObject("""{"event":"none"}""")))
        assertEquals(
            KdcEvent.PairRequest("abc", "Pixel", "54DC916E"),
            KdcEvent.parse(JSONObject("""{"event":"pair_request","id":"abc","name":"Pixel","key":"54DC916E"}""")),
        )
        assertEquals(
            KdcEvent.Ping("abc", "hello"),
            KdcEvent.parse(JSONObject("""{"event":"plugin","id":"abc","plugin":{"plugin":"ping","message":"hello"}}""")),
        )
        assertEquals(
            KdcEvent.Ping("abc", null),
            KdcEvent.parse(JSONObject("""{"event":"plugin","id":"abc","plugin":{"plugin":"ping","message":null}}""")),
        )
        assertEquals(KdcEvent.Other("stopped"), KdcEvent.parse(JSONObject("""{"event":"stopped"}""")))
        assertEquals(KdcEvent.PairFailed("abc", "timed_out"), KdcEvent.parse(JSONObject("""{"event":"pair_failed","id":"abc","reason":"timed_out"}""")))
    }

    @Test
    fun addresses() {
        assertEquals(listOf("100.64.0.2", "192.168.1.9"), KdeConnect.parseAddresses(" 100.64.0.2, 192.168.1.9\n100.64.0.2 "))
        assertEquals(emptyList<String>(), KdeConnect.parseAddresses(""))
    }
}
