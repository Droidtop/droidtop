# runtime-remote-stream

Remote PC game streaming, GameStream-protocol compatible — targets Sunshine
(and Apollo, a Sunshine fork) hosts primarily, since real NVIDIA GameStream
is discontinued. Vendors [vendor/moonlight-common-c](../vendor/moonlight-common-c)
(GPL-3.0) for the actual protocol work: pairing, app-list retrieval, stream
session setup.

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

Interfaces and native JNI signatures only — `remotestream_jni.cpp`'s three
entry points are unimplemented, and none of this has been compiled (no NDK/
OpenSSL cross-build available in this environment). moonlight-common-c is
mature and widely cross-compiled elsewhere (every Moonlight client ships it
for multiple platforms including Android), so this is comparatively
lower-risk than `host-bridge`'s from-scratch Wayland work — but it is
unverified here specifically.
