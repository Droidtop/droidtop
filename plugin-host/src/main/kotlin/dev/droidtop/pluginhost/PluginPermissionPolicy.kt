package dev.droidtop.pluginhost

/** A permission check's place in the category/call hierarchy and its manifest-defined fallback. */
data class PluginPermissionRequest(
    val category: String,
    val call: String,
    val default: GrantState,
)

/** Explicit choices use separate key spaces so category and API call ids cannot shadow one another. */
object PluginPermissionPolicy {
    const val CATEGORY_PREFIX = "category:"
    const val CALL_PREFIX = "call:"

    fun categoryKey(category: String): String = CATEGORY_PREFIX + category
    fun callKey(call: String): String = CALL_PREFIX + call

    /** The most specific explicit choice wins: call, category, then the manifest default. */
    fun resolve(request: PluginPermissionRequest, choices: Map<String, GrantState>): GrantState =
        choices[callKey(request.call)]
            ?: choices[categoryKey(request.category)]
            ?: request.default
}
