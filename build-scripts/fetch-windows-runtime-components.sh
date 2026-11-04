#!/usr/bin/env bash
# Puts the Windows runtime files droidtop does not build (the arm64
# libvortekrenderer and libkgslshim, the Box64/FEXCore/WowBox64 builds, the
# input DLLs, the redirect libraries) into runtime-windows/components/, where
# runtime-windows/build.gradle.kts reads its jniLibs and assets
# (docs/SPEC.md 9). Each comes from the windows-runtime release of
# Droidtop/droidtop-components or, where that copy is not there, from the
# maker's URL in runtime-windows/components.pin. Either way only bytes with
# the pinned SHA-256 are kept: a mismatch fails the build rather than
# packaging something else.
#
# A file already in place with the pinned SHA-256 is not fetched again. A
# download goes to <name>.part beside its destination and is renamed only
# once it checks out, so an interrupted run leaves no wrong file behind.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PIN="$ROOT/runtime-windows/components.pin"
DEST="$ROOT/runtime-windows/components"
RELEASE="https://github.com/Droidtop/droidtop-components/releases/download/windows-runtime"

grep -v '^#' "$PIN" | while read -r path sha maker; do
    [ -n "$path" ] || continue
    out="$DEST/$path"
    if [ -f "$out" ] && echo "$sha  $out" | sha256sum -c --quiet - 2>/dev/null; then
        continue
    fi
    mkdir -p "$(dirname "$out")"
    name=$(basename "$path")
    got=""
    for url in "$RELEASE/$name" "$maker"; do
        if curl -fsSL --retry 5 --retry-delay 10 -o "$out.part" "$url" &&
            echo "$sha  $out.part" | sha256sum -c --quiet -; then
            got=$url
            break
        fi
        echo "not from $url" >&2
    done
    [ -n "$got" ] || { echo "no copy of $name with SHA-256 $sha" >&2; exit 1; }
    mv -f "$out.part" "$out"
    echo "$path <- $got"
done
