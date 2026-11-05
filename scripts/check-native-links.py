#!/usr/bin/env python3
"""Every native library in the APK can resolve the symbols it imports (Droidtop/tracker#857, 2026-11-05).

Release 2967 crashed every Windows game on the console at its first Present: libvortekrenderer.so imports
ArrayList_add from "libwinlator.so", which GameNative's prebuilt libwinlator_11.so provides (it carries that SONAME),
and the copy droidtop built from source had neither the SONAME nor the function. Nothing caught it before a device
did. This check reads each library in the APK and asks, as Android's linker would, whether every strong undefined
dynamic symbol is defined by a library its DT_NEEDED closure can reach:

  - a NEEDED name is matched against the APK's libraries by file name AND by DT_SONAME (the linker reuses a loaded
    library whose SONAME matches, which is exactly how vortek reaches libwinlator_11.so), and against the NDK's
    platform stubs (sysroot/usr/lib/<triple>/<api>/ and sysroot/usr/lib/<triple>/);
  - weak undefined symbols are skipped (the linker leaves them null);
  - a library that is not loaded into droidtop's own process but preloaded into another (a guest's LD_PRELOAD,
    where the program defines what it imports) is named with --other-process and only reported.

Several APK libraries can share a SONAME; all of them count, so the check proves "some match defines it", not
which one wins at run time.

Usage: check-native-links.py <unzipped APK root, holding lib/<abi>/> <NDK sysroot> [--api N] [--other-process lib.so]...
Exit 1 with a list of library: symbol pairs when anything is missing.
"""
import argparse
import os
import re
import subprocess
import sys

TRIPLES = {
    "arm64-v8a": "aarch64-linux-android",
    "x86_64": "x86_64-linux-android",
    "armeabi-v7a": "arm-linux-androideabi",
    "x86": "i686-linux-android",
}
READELF = os.environ.get("READELF", "readelf")


def readelf(*args):
    return subprocess.run([READELF, "-W", *args], check=True, capture_output=True, text=True).stdout


class Lib:
    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)
        dynamic = readelf("-d", path)
        self.needed = re.findall(r"\(NEEDED\)\s+Shared library: \[(.+?)\]", dynamic)
        soname = re.findall(r"\(SONAME\)\s+Library soname: \[(.+?)\]", dynamic)
        self.soname = soname[0] if soname else self.name
        self.defined, self.undefined = set(), set()
        for line in readelf("--dyn-syms", path).splitlines():
            cols = line.split()
            # Num: Value Size Type Bind Vis Ndx Name
            if len(cols) < 8 or not cols[0].rstrip(":").isdigit():
                continue
            bind, ndx, name = cols[4], cols[6], cols[7].split("@")[0]
            if not name or cols[3] in ("SECTION", "FILE"):
                continue
            if ndx == "UND":
                if bind != "WEAK":
                    self.undefined.add(name)
            elif bind in ("GLOBAL", "WEAK", "GNU_UNIQUE"):
                self.defined.add(name)


def stubs_for(sysroot, abi, api):
    base = os.path.join(sysroot, "usr", "lib", TRIPLES[abi])
    dirs = [base]
    if os.path.isdir(base):
        levels = sorted(int(d) for d in os.listdir(base) if d.isdigit() and int(d) <= api)
        if levels:
            dirs.insert(0, os.path.join(base, str(levels[-1])))
    found = {}
    for d in dirs:
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            if f.endswith(".so") and f not in found:
                found[f] = os.path.join(d, f)
    return found


def check_abi(root, abi, sysroot, api, other):
    libdir = os.path.join(root, "lib", abi)
    apk = [Lib(os.path.join(libdir, f)) for f in sorted(os.listdir(libdir)) if f.endswith(".so")]
    stub_paths = stubs_for(sysroot, abi, api)
    stub_cache = {}

    def resolve(name):
        matches = [lib for lib in apk if lib.name == name or lib.soname == name]
        if matches:
            return matches
        if name in stub_paths:
            if name not in stub_cache:
                stub_cache[name] = Lib(stub_paths[name])
            return [stub_cache[name]]
        return None

    problems, notes = [], []
    for lib in apk:
        seen, queue, available, unknown = {lib.path}, list(lib.needed), set(lib.defined), []
        while queue:
            name = queue.pop(0)
            matches = resolve(name)
            if matches is None:
                unknown.append(name)
                continue
            for match in matches:
                if match.path in seen:
                    continue
                seen.add(match.path)
                available |= match.defined
                queue.extend(match.needed)
        missing = sorted(lib.undefined - available)
        target = notes if lib.name in other else problems
        for name in unknown:
            target.append(f"{abi}/{lib.name}: NEEDED {name} is neither in the APK nor an NDK platform library")
        for sym in missing:
            target.append(f"{abi}/{lib.name}: {sym}")
    return len(apk), problems, notes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("root")
    parser.add_argument("sysroot")
    parser.add_argument("--api", type=int, default=35)
    parser.add_argument("--other-process", action="append", default=[])
    args = parser.parse_args()
    abis = [a for a in sorted(os.listdir(os.path.join(args.root, "lib"))) if a in TRIPLES]
    if not abis:
        sys.exit("check-native-links: no lib/<abi>/ in " + args.root)
    total, problems, notes = 0, [], []
    for abi in abis:
        count, p, n = check_abi(args.root, abi, args.sysroot, args.api, set(args.other_process))
        total += count
        problems += p
        notes += n
    for line in notes:
        print("note (loaded into another process): " + line)
    if problems:
        print(f"check-native-links: {len(problems)} unresolved import(s) in the APK's {total} libraries:")
        for line in problems:
            print("  " + line)
        sys.exit(1)
    print(f"check-native-links: every import of the APK's {total} libraries ({', '.join(abis)}) resolves.")


if __name__ == "__main__":
    main()
