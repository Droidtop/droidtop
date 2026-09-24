# runtime-linux-noroot

No-root Linux container backend. Unlike `runtime-linux-root`, there's no
upstream project to fork here — build target is the pattern used by Termux's
`proot-distro` and [Box64Droid](https://github.com/Ilya114/Box64Droid):
ptrace-based syscall interception via proot, a downloaded rootfs (Debian/
Ubuntu/Alpine), no kernel namespaces/cgroups.

It implements the same `ContainerRuntime` contract as `runtime-linux-root` —
same primary/sibling model, same Wayland/PulseAudio socket sharing — so this
is a drop-in alternative selected automatically when the device isn't rooted.
Expect measurably worse performance and weaker isolation than the DroidSpaces
path; that's the accepted tradeoff for not requiring root.

`ProotRuntime` is that backend: Termux's proot (`vendor/proot`, packaged as
`libproot.so` by `build-scripts/build-vendor-deps.sh`) run from
`nativeLibraryDir`, with each `ContainerLayout` host directory as a proot
bind. Its class comment and docs/SPEC.md §3 carry the details.
