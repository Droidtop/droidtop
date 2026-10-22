#!/usr/bin/env bash
# Puts the aria2 download engine's Android library (docs/SPEC.md 12a "One download
# engine") into net-core/aria2-engine/repo/, laid out as a Maven repository that
# settings.gradle.kts reads, so :net-core depends on it as
# io.github.bi0shacker001:aria2-engine:<version> like any library. The AAR (libaria2 for
# arm64-v8a and x86_64, the engine service, its broker and the Kotlin API) is built and
# released by bi0shacker001/aria2-engine; the release tag and the AAR's SHA-256 are
# net-core/aria2-engine.pin. The release's own SHA256SUMS must agree with the pin, and a
# mismatch in either fails the build rather than packaging something else.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PIN="$ROOT/net-core/aria2-engine.pin"
DEST="$ROOT/net-core/aria2-engine"
tag=$(sed -n 's/^tag=//p' "$PIN")
sha=$(sed -n 's/^sha256=//p' "$PIN")
: "${tag:?no tag in $PIN}" "${sha:?no sha256 in $PIN}"
version=${tag#v}
if [ -f "$DEST/.pin" ] && [ "$(cat "$DEST/.pin")" = "$tag $sha" ]; then
    echo "aria2 engine $tag already in place"
    exit 0
fi
base="https://github.com/bi0shacker001/aria2-engine/releases/download/$tag"
work=$(mktemp -d)
curl -fsSL --retry 5 --retry-delay 10 -o "$work/aria2-engine-$version.aar" "$base/aria2-engine-$version.aar"
curl -fsSL --retry 5 --retry-delay 10 -o "$work/SHA256SUMS" "$base/SHA256SUMS"
echo "$sha  $work/aria2-engine-$version.aar" | sha256sum -c -
listed=$(awk -v f="aria2-engine-$version.aar" '$2 == f { print $1 }' "$work/SHA256SUMS")
if [ "$listed" != "$sha" ]; then
    echo "the release's SHA256SUMS lists '$listed' for the AAR, the pin says $sha" >&2
    exit 1
fi
# A changed pin writes its version beside any older one; Gradle resolves only the pinned version.
dir="$DEST/repo/io/github/bi0shacker001/aria2-engine/$version"
mkdir -p "$dir"
cp "$work/aria2-engine-$version.aar" "$dir/"
# The AAR's only dependency is kotlinx-coroutines, which droidtop already has.
cat > "$dir/aria2-engine-$version.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.bi0shacker001</groupId>
  <artifactId>aria2-engine</artifactId>
  <version>$version</version>
  <packaging>aar</packaging>
</project>
POM
echo "$tag $sha" > "$DEST/.pin"
find "$DEST" -maxdepth 8 -type f
