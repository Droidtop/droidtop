package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * The conformance script (docs/plugin-api.md 1.3 and 3 I2, Droidtop/tracker#71) for a plugin in a [FakeHost]: every
 * case of `conformance.json`, the one file the python fake host (`droidtop_fake_host.py`) reads too. A case gets a
 * fresh host with its own grants. A kind's fixture that fails a case is a bug in that kind's adapter or fixture.
 */
object Conformance {
    /** The cases as shipped in this module. */
    fun cases(): JSONObject = JSONObject(
        Conformance::class.java.getResourceAsStream("/dev/droidtop/pluginhost/conformance.json")!!.bufferedReader().use { it.readText() },
    )

    /** One case's outcome: [failures] is empty when it passed. */
    data class Result(val name: String, val failures: List<String>) {
        val passed: Boolean get() = failures.isEmpty()
    }

    /** Runs every case against a fresh host from [newHost]. */
    fun run(newHost: () -> FakeHost, suite: JSONObject = cases()): List<Result> {
        val plain = Regex(suite.getString("plainMessage"))
        val cases = suite.getJSONArray("cases")
        return (0 until cases.length()).map { i ->
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val failures = runCatching {
                val host = newHost()
                case.optJSONObject("grants")?.let { grants ->
                    grants.keys().forEach { permission ->
                        host.grant(permission, if (grants.getString(permission) == "granted") GrantState.GRANTED else GrantState.DENIED)
                    }
                }
                val outcome = case.optJSONObject("job")?.let { host.runJob(callOf(it)) }
                    ?: host.handle(callOf(case.getJSONObject("call"))).toJson()
                check(outcome, case.getJSONArray("expect"), plain)
            }.getOrElse { listOf("crashed: ${it::class.java.simpleName}: ${it.message}") }
            Result(name, failures)
        }
    }

    private fun callOf(json: JSONObject) = PluginCall(
        callId = "conformance",
        deadlineMs = PluginRunner.CALL_TIMEOUT_MS,
        point = json.getString("point"),
        version = json.optInt("version", 1),
        op = json.getString("op"),
        args = json.optJSONObject("args") ?: JSONObject(),
    )

    /** Every expectation [outcome] misses, in words. */
    fun check(outcome: JSONObject, expect: JSONArray, plain: Regex): List<String> = (0 until expect.length()).mapNotNull { i ->
        val e = expect.getJSONObject(i)
        val path = e.getString("path")
        val value = at(outcome, path)
        when {
            e.has("equals") -> {
                val want = e.get("equals")
                if (same(value, want)) null else "$path is ${value ?: "missing"}, wanted $want"
            }
            e.optBoolean("present") -> if (value == null) "$path is missing" else null
            e.optBoolean("plain") -> when {
                value !is String || value.isBlank() -> "$path is not a sentence"
                plain.containsMatchIn(value) -> "$path is not one plain sentence: \"$value\""
                else -> null
            }
            else -> "the expectation on $path says nothing to check"
        }
    }

    private fun same(value: Any?, want: Any): Boolean = when {
        value is Number && want is Number -> value.toDouble() == want.toDouble()
        else -> value == want
    }

    /** The value at a dotted [path]; a number names an array element. */
    fun at(json: JSONObject, path: String): Any? = path.split('.').fold(json as Any?) { node, key ->
        when (node) {
            is JSONObject -> if (node.has(key) && !node.isNull(key)) node.get(key) else null
            is JSONArray -> key.toIntOrNull()?.let { if (it in 0 until node.length()) node.get(it) else null }
            else -> null
        }
    }
}
