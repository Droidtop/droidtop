package dev.droidtop.runtime.remotestream

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider

/**
 * Surfaces every app on every paired [RemoteHost] as a REMOTE_STREAM
 * LibraryEntry, same as any local Wine profile or Linux container app —
 * this is the whole point of building the library model the way
 * library-core does. Launching one starts a Moonlight stream instead of a
 * local process; the window it produces is still just a DisplayOutput
 * target like anything else (see MoonlightClient.startStream).
 */
class RemoteStreamProvider(
    private val discovery: RemoteHostDiscovery,
    private val client: MoonlightClient,
) : LibraryProvider {
    override val kinds = setOf(LibraryEntryKind.REMOTE_STREAM)

    override suspend fun scan(): List<LibraryEntry> {
        return discovery.discover()
            .filter { it.paired }
            .flatMap { host -> client.fetchAppList(host).map { app -> host to app } }
            .map { (host, app) ->
                LibraryEntry(
                    id = "${host.address}:${app.appId}",
                    title = app.name,
                    kind = LibraryEntryKind.REMOTE_STREAM,
                    artworkUri = app.artworkUri,
                )
            }
    }

    override suspend fun launch(entry: LibraryEntry) {
        TODO("Resolve entry.id back to (RemoteHost, RemoteApp), call client.startStream")
    }
}
