package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BodyRelayContractTest {
    @Test fun `the API26 consumer uses the canonical body-only contract`() {
        val contract = javaClass.classLoader!!.getResourceAsStream("web-download-v1.json")!!.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        fun JSONArray.strings() = (0 until length()).map { getString(it) }.toSet()
        assertEquals(26, contract.getInt("minimumApi"))
        assertEquals(BodyDownloadRelay.CAPABILITY, contract.getString("relay"))
        assertEquals(BodyDownloadRelay.CAPABILITY, contract.getString("capability"))
        assertEquals(contract.getJSONArray("provider").strings(), IWebDownloadProvider::class.java.declaredMethods.map { it.name }.toSet())
        assertEquals(contract.getJSONArray("host").strings(), IWebDownloadHost::class.java.declaredMethods.map { it.name }.toSet())
        for (type in listOf(IWebDownloadHost::class.java, IWebDownloadProvider::class.java)) {
            for (method in type.declaredMethods) {
                assertFalse(method.returnType.name.contains("Surface"))
                assertFalse(method.parameterTypes.any { it.name.contains("Surface") })
            }
        }
    }
}
