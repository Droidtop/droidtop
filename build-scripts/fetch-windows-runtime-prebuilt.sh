#!/usr/bin/env bash
# Puts the Windows runtime's re-hosted prebuilt binaries
# (windows-runtime-prebuilt.yml) into runtime-windows/prebuilt/, where
# runtime-windows/build.gradle.kts reads its arm64 jniLibs and its assets.
# The release and the archive's SHA-256 are runtime-windows/prebuilt.pin;
# a mismatch fails the build rather than packaging something else.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PIN="$ROOT/runtime-windows/prebuilt.pin"
DEST="$ROOT/runtime-windows/prebuilt"
tag=$(sed -n 's/^tag=//p' "$PIN")
sha=$(sed -n 's/^sha256=//p' "$PIN")
: "${tag:?no tag in $PIN}" "${sha:?no sha256 in $PIN}"
if [ -f "$DEST/.pin" ] && [ "$(cat "$DEST/.pin")" = "$tag $sha" ]; then
    echo "prebuilts $tag already in place"
    exit 0
fi
work=$(mktemp -d)
trap 'rm -rf -- "${work:?}"' EXIT
curl -fsSL --retry 5 --retry-delay 10 -o "$work/p.tar.zst" \
    "https://github.com/Droidtop/droidtop/releases/download/$tag/windows-runtime-prebuilt.tar.zst"
echo "$sha  $work/p.tar.zst" | sha256sum -c -
rm -rf -- "${DEST:?}"
mkdir -p "$DEST"
zstd -dc "$work/p.tar.zst" | tar -C "$DEST" -xf -
echo "$tag $sha" > "$DEST/.pin"
find "$DEST" -maxdepth 2 | head -20
