#!/usr/bin/env bash
# Puts droidtop-agent's Android library (libdroidtop_agent.so, arm64-v8a and
# x86_64; docs/SPEC.md 7o) into net-core/agent/jniLibs/, where
# net-core/build.gradle.kts reads it. The library is built and released by the
# Droidtop/droidtop-agent repository; the release tag and the archive's
# SHA-256 are net-core/agent-lib.pin, and a mismatch fails the build rather
# than packaging something else.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PIN="$ROOT/net-core/agent-lib.pin"
DEST="$ROOT/net-core/agent"
tag=$(sed -n 's/^tag=//p' "$PIN")
sha=$(sed -n 's/^sha256=//p' "$PIN")
: "${tag:?no tag in $PIN}" "${sha:?no sha256 in $PIN}"
if [ -f "$DEST/.pin" ] && [ "$(cat "$DEST/.pin")" = "$tag $sha" ]; then
    echo "droidtop-agent library $tag already in place"
    exit 0
fi
work=$(mktemp -d)
trap 'rm -rf -- "${work:?}"' EXIT
curl -fsSL --retry 5 --retry-delay 10 -o "$work/a.tar.zst" \
    "https://github.com/Droidtop/droidtop-agent/releases/download/$tag/droidtop-agent-android.tar.zst"
echo "$sha  $work/a.tar.zst" | sha256sum -c -
rm -rf -- "${DEST:?}"
mkdir -p "$DEST"
zstd -dc "$work/a.tar.zst" | tar -C "$DEST" -xf -
echo "$tag $sha" > "$DEST/.pin"
find "$DEST" -maxdepth 3 -type f
