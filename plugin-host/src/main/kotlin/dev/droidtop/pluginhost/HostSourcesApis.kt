package dev.droidtop.pluginhost

/**
 * `sources.parse_thread {html}` (docs/plugin-api.md 1.11, point 3; Droidtop/tracker#838): a forum thread's download post
 * read by droidtop's one parser ([ThreadDownloads]) into the `download` bundle's entries, so every source plugin gives
 * droidtop the same shape and droidtop picks the build, the system and the mirror. It reads only the text it is given:
 * no network, no files, so it needs no permission.
 */
internal object HostSourcesApis {
    val ops: List<HostOp> = listOf(
        HostOp("sources", "parse_thread") { _, _, args ->
            val html = args.optString("html")
            if (html.isEmpty()) throw BrokerException(PluginErrorCode.INVALID_ARGS, "parse_thread needs the page's html")
            ThreadDownloads.toJson(ThreadDownloads.read(html))
        },
    )
}
