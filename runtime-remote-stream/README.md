# runtime-remote-stream

Remote PC game streaming, GameStream-protocol compatible — targets Sunshine
(and Apollo, a Sunshine fork) hosts primarily, since real NVIDIA GameStream
is discontinued. Vendors [vendor/moonlight-common-c](../vendor/moonlight-common-c)
(GPL-3.0) for the actual video/audio streaming session. Pairing and app-list
retrieval are NOT part of that library (confirmed by inspecting its public
headers — it's streaming-protocol/RTP/RTSP only); they're a plain HTTPS+XML
REST layer, implemented directly in Kotlin here (`MoonlightPairing.kt`,
`GameStreamHttpClient.kt`, `ClientIdentity.kt`, `AppListParser.kt`), ported
from moonlight-android's real, public `NvHTTP`/`PairingManager`/
`AndroidCryptoProvider`.

Surfaces every app on every paired host as a `REMOTE_STREAM` `LibraryEntry`
(`library-core`) — a remote game is not a special case in the UI, it's the
same list item type as a local Wine profile or container app.

## Licensing — read before building against this module

`moonlight-common-c` is **GPL-3.0**. That's consistent with the rest of
this project (`gamenative`, `droidspaces` are also GPL-3.0 — see
[docs/SPEC.md §8](../docs/SPEC.md#8-licensing)), so it doesn't change the
project's overall licensing position, but it's worth naming explicitly: this
module cannot be extracted and used under a more permissive license without
separately negotiating that with upstream.

**Build constraint, not optional:** `moonlight-common-c`'s own README states
that substituting a standard/system ENet for its bundled fork "will cause
your client to crash when connecting to recent versions of GeForce
Experience [and Sunshine]." `vendor/moonlight-common-c/enet` is pinned to
exactly that fork (`cgutman/enet`) as a nested submodule — never point the
build at a system libenet.

## What's NOT in moonlight-common-c and had to be added here

- **LAN host discovery** — moonlight-common-c is protocol/transport only.
  `RemoteHostDiscovery` needs porting from a platform client (moonlight-
  android's discovery code is the direct reference) rather than coming from
  the vendored library for free.
- **Pairing UX** — the classic flow is "PIN shown on the client, typed into
  the host's Sunshine web UI." Sunshine's REST API also accepts `POST
  /api/pin` directly, which would let this app complete pairing without the
  user ever opening Sunshine's web UI on the host — worth preferring that
  path when the host is confirmed to be Sunshine specifically (not
  necessarily available on legacy GFE hosts).

## Status

**Builds successfully end to end**, verified against a real Android NDK
build (WSL2, NDK 27.0.12077973): `vendor/mbedtls` (see CMakeLists.txt's
`USE_MBEDTLS` note — chosen over OpenSSL because it's CMake-native and
cross-compiles through the same NDK toolchain file Gradle already passes
in, no separate Configure/Perl step needed) plus `vendor/moonlight-common-c`
and its pinned ENet fork all compile and link into `libremotestream.so`.

Real pairing (`MoonlightClient.pair`) and app-list retrieval
(`MoonlightClient.fetchAppList`) are implemented in pure Kotlin, faithfully
ported from moonlight-android's reference source — not stubs. Neither one
touches the native library at all.

What's still not done: `remotestream_jni.cpp`'s one remaining JNI entry
point, `nativeStartStream`, is unimplemented — the native library builds,
but doesn't stream anything yet. That's the actual next piece of work here,
not any further build/toolchain setup. `RemoteHostDiscovery` (LAN host
discovery) is also still unimplemented — see "What's NOT in
moonlight-common-c" above.
