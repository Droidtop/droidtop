#!/usr/bin/env bash
# Puts the lsfg-vk layer (lsfg-vk-layer.yml) into runtime-windows/lsfg/jniLibs/,
# where runtime-windows/build.gradle.kts reads it. The release and the
# archive's SHA-256 are runtime-windows/lsfg-layer.pin; a mismatch fails the
# build rather than packaging something else.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PIN="$ROOT/runtime-windows/lsfg-layer.pin"
DEST="$ROOT/runtime-windows/lsfg"
tag=$(sed -n 's/^tag=//p' "$PIN")
sha=$(sed -n 's/^sha256=//p' "$PIN")
: "${tag:?no tag in $PIN}" "${sha:?no sha256 in $PIN}"
if [ -f "$DEST/.pin" ] && [ "$(cat "$DEST/.pin")" = "$tag $sha" ]; then
    echo "lsfg layer $tag already in place"
    exit 0
fi
work=$(mktemp -d)
trap 'rm -rf -- "${work:?}"' EXIT
curl -fsSL --retry 5 --retry-delay 10 -o "$work/l.tar.zst" \
    "https://github.com/Droidtop/droidtop/releases/download/$tag/lsfg-vk-layer.tar.zst"
echo "$sha  $work/l.tar.zst" | sha256sum -c -
rm -rf -- "${DEST:?}"
mkdir -p "$DEST"
zstd -dc "$work/l.tar.zst" | tar -C "$DEST" -xf -
echo "$tag $sha" > "$DEST/.pin"
find "$DEST" -maxdepth 3 -type f
