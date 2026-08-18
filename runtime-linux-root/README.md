# runtime-linux-root

Forked from [vendor/droidspaces](../vendor/droidspaces) (GPL-3.0). Namespace +
cgroup based Linux containers — real isolation, PID 1 init systems, OverlayFS
storage. **Requires root** (KernelSU, APatch, or Magisk in Daemon Mode).

## What changes vs. upstream

Upstream DroidSpaces auto-launches a private Termux:X11 instance per
container. We don't want that — see [docs/SPEC.md](../docs/SPEC.md) for the
full rationale. Instead:

- One container per device is flagged `PRIMARY` and boots a desktop
  compositor (vendor/sway) instead of an app.
- Every other container is a `SIBLING` that shares the primary's Wayland +
  PulseAudio sockets via bind mount, so its GUI apps appear as windows on the
  one shared desktop — the actual distrobox integration model, not isolation.

Rootfs images (both the primary container's base + vendor/sway, and any
sibling container's distro of choice) are pulled as OCI images via
`RootfsPuller` (runtime-common), backed by `vendor/crane` — same registries
`docker pull` uses, no daemon required. This replaces upstream DroidSpaces'
bespoke `.tzst`/ext4-image install flow.

## Status

Stub — `DroidSpacesRuntime` isn't ported yet. Porting order: (1) primary
bootstrap profile including vendor/sway, (2) sibling socket-sharing bind
mounts, (3) strip the upstream Termux:X11 auto-launch path entirely,
(4) `RootfsPuller` implementation on top of `vendor/crane`.
