# runtime-linux-noroot

No-root Linux container backend. Unlike `runtime-linux-root`, there's no
upstream project to fork here — build target is the pattern used by Termux's
`proot-distro` and [Box64Droid](https://github.com/Ilya114/Box64Droid):
ptrace-based syscall interception via proot, a downloaded rootfs (Debian/
Ubuntu/Alpine), no kernel namespaces/cgroups.

Must implement the same `ContainerRuntime` contract as `runtime-linux-root` —
same primary/sibling model, same Wayland/PulseAudio socket sharing — so this
is a drop-in alternative selected automatically when the device isn't rooted.
Expect measurably worse performance and weaker isolation than the DroidSpaces
path; that's the accepted tradeoff for not requiring root.

## Status

Stub only. This is the single largest piece of genuinely new engineering in
the repo — see [docs/SPEC.md](../docs/SPEC.md) for scope and open risks.
