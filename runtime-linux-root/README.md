# runtime-linux-root

Forked from [vendor/droidspaces](../vendor/droidspaces) (GPL-3.0). Namespace +
cgroup based Linux containers — real isolation, PID 1 init systems, OverlayFS
storage. **Requires root** (KernelSU, APatch, or Magisk in Daemon Mode).

Not a JNI/library integration — droidspaces is designed and documented as a
command-line tool ([Linux-CLI.md](../vendor/droidspaces/Documentation/Linux-CLI.md)),
so `DroidSpacesRuntime` drives the real `droidspaces` binary as a subprocess
via `su -c` ([`RootProcess`](src/main/kotlin/dev/droidtop/runtime/linux/root/RootProcess.kt)),
the same way any human operator would from a root shell.

## What changes vs. upstream

Upstream DroidSpaces auto-launches a private Termux:X11 instance per
container (its own built-in GUI feature — see
[Graphics-and-Audio.md](../vendor/droidspaces/Documentation/Graphics-and-Audio.md)).
We don't use that at all — see [docs/SPEC.md](../docs/SPEC.md) for the full
rationale. Instead:

- One container per device is flagged `PRIMARY` and is where vendor/sway
  (headless-output build) is meant to run as the shared desktop compositor.
- Every container — primary and siblings alike — bind-mounts the **same**
  host directory to a fixed in-container path (`/run/droidtop-sockets`), with
  `XDG_RUNTIME_DIR` pointed at it via an injected env file. Whichever
  container's compositor creates the Wayland socket there, every other
  container sharing that bind mount sees the exact same socket file — this
  is the real mechanism distrobox uses on desktop Linux to integrate
  containers with a host desktop, expressed here through droidspaces'
  generic `--bind-mount` flag rather than a purpose-built one.
  `:host-bridge` connects to that same host directory directly, since it
  runs outside any container.
- PulseAudio is the one piece where reinventing distrobox's approach wasn't
  needed: droidspaces already bridges Android's audio HAL to a single
  host-side PulseAudio daemon and bind-mounts its socket into any container
  with `enable_pulseaudio=1` — used as-is via `DroidSpacesContainerConfig`.

## Status

**Builds and packages for real.** `build-scripts/build-vendor-deps.sh`
cross-compiles the `droidspaces` binary itself — a single static musl-libc
executable (~430KB, verified), needing only a prebuilt
`aarch64-linux-musl-gcc` toolchain and no other dependencies (static linking
means it only needs the Linux kernel syscall ABI, which Android provides
regardless of bionic vs musl userland — this is *much* simpler than
`:host-bridge`'s wayland-client cross-compile). The binary is bundled as an
APK asset (`assets/bin/droidspaces-arm64-v8a`, confirmed present in a real
built APK) and extracted to a real executable file on first use
(`DroidSpacesBinary`).

`DroidSpacesRuntime` implements the full container lifecycle for real,
against droidspaces' actual documented CLI and `.config` file format:
container creation (writing a `.config` + env file per container, with the
primary/sibling socket-sharing bind mount wired in), `start`/`stop`/
`destroy` mapped to the real CLI subcommands, and `checkSystemRequirements()`
wrapping `droidspaces check` (verifies kernel namespace/cgroup support —
worth calling before ever attempting `createPrimary`).

**What's genuinely NOT done, and is the actual remaining gap** — not this
class's container orchestration, which is real:
- `RootfsPuller` (in `runtime-common`) has no implementation at all yet.
  Nothing can actually pull an OCI image, so `createPrimary`/`createSibling`
  will fail at that step until it exists.
- No OCI image with vendor/sway pre-installed and configured to boot into
  `CONTAINER_SOCKET_DIR` has been built or published — `PRIMARY_IMAGE_REFERENCE`
  is a placeholder.
- `ContainerRuntime.createSibling()` takes no parameters at all, so there's
  currently no way for a caller to pick which distro a sibling should run —
  falls back to a hardcoded Debian reference for now.
- **Nothing here has been run against a real device.** `su -c` is the
  standard interface every root solution (Magisk/KernelSU/APatch) provides,
  and the CLI/config syntax matches droidspaces' own documentation exactly,
  but no rooted device was available in the environment this was written in
  to actually exercise it end to end.
