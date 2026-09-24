#!/usr/bin/env python3
"""Lists the arm64-v8a native libraries a fat APK has no x86_64 build of.

An Android-x86 device with ARM translation installs a fat APK as x86_64 only
when its x86_64 set holds every arm64-v8a library name (Android-x86's
core/jni/abipicker/ABIPicker.cpp; docs/SPEC.md 3 and 10b). This prints the
names that stop that, read from the built APK, so the list is never a note
that drifts from the build.

Usage: abi_sets.py <apk> [--require-complete]

Without the flag it reports and exits 0, as a GitHub Actions warning; with
it a missing name fails the step. SPEC 10b says when the flag goes on.
"""
import sys
import zipfile


def libs(names, abi):
    prefix = f"lib/{abi}/"
    return {n[len(prefix):] for n in names if n.startswith(prefix) and n.endswith(".so")}


def main():
    args = sys.argv[1:]
    require = "--require-complete" in args
    apks = [a for a in args if not a.startswith("--")]
    if len(apks) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    with zipfile.ZipFile(apks[0]) as apk:
        names = apk.namelist()
    arm64 = libs(names, "arm64-v8a")
    x86_64 = libs(names, "x86_64")
    missing = sorted(arm64 - x86_64)
    print(f"arm64-v8a: {len(arm64)} libraries, x86_64: {len(x86_64)}")
    if not missing:
        print("x86_64 holds every arm64-v8a library.")
        return 0
    print(f"{len(missing)} arm64-v8a libraries have no x86_64 build:")
    for name in missing:
        print(f"  {name}")
    level = "error" if require else "warning"
    print(f"::{level}::{len(missing)} arm64-v8a libraries have no x86_64 build: {' '.join(missing)}")
    return 1 if require else 0


if __name__ == "__main__":
    sys.exit(main())
