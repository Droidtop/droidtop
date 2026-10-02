package dev.droidtop.pluginhost

/** A permission check's place in the category/call hierarchy and its manifest-defined fallback. */
data class PluginPermissionRequest(
    val category: String,
    val call: String,
    val default: GrantState,
)

/** Explicit choices use separate key spaces so category and API call ids cannot shadow one another. */
object PluginPermissionPolicy {
    fun categoryKey(category: String): String = "category:$category"
    fun callKey(call: String): String = "call:$call"

    /** The most specific explicit choice wins: call, category, then the manifest default. */
    fun resolve(request: PluginPermissionRequest, choices: Map<String, GrantState>): GrantState =
        choices[callKey(request.call)]
            ?: choices[categoryKey(request.category)]
            ?: request.default
}
