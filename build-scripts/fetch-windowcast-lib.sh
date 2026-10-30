#!/usr/bin/env bash
# Puts windowcast's Android library (docs/SPEC.md 7a, "Remote streaming is windowcast's library") into
# app/windowcast-lib/repo/, laid out as a Maven repository that settings.gradle.kts reads, so :app depends on it
# as dev.windowcast:windowcast-android:<version> like any library. The AAR (client-core for arm64-v8a and
# x86_64, the session, decoder, input and sign-in types) is built and attached to each windowcast release by
# Droidtop/windowcast's "Viewer release" workflow; the release tag and the AAR's SHA-256 are app/windowcast.pin.
# The release's own SHA256SUMS-android-library must agree with the pin, and a mismatch in either fails the
# build rather than packaging something else.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PIN="$ROOT/app/windowcast.pin"
DEST="$ROOT/app/windowcast-lib"
tag=$(sed -n 's/^tag=//p' "$PIN")
sha=$(sed -n 's/^sha256=//p' "$PIN")
: "${tag:?no tag in $PIN}" "${sha:?no sha256 in $PIN}"
version=${tag#v}
if [ -f "$DEST/.pin" ] && [ "$(cat "$DEST/.pin")" = "$tag $sha" ]; then
    echo "windowcast library $tag already in place"
    exit 0
fi
base="https://github.com/Droidtop/windowcast/releases/download/$tag"
work=$(mktemp -d)
trap 'rm -rf -- "${work:?}"' EXIT
aar="windowcast-android-$version.aar"
curl -fsSL --retry 5 --retry-delay 10 -o "$work/$aar" "$base/$aar"
curl -fsSL --retry 5 --retry-delay 10 -o "$work/SHA256SUMS-android-library" "$base/SHA256SUMS-android-library"
echo "$sha  $work/$aar" | sha256sum -c -
listed=$(awk -v f="$aar" '$2 == f { print $1 }' "$work/SHA256SUMS-android-library")
if [ "$listed" != "$sha" ]; then
    echo "the release's SHA256SUMS-android-library lists '$listed' for the AAR, the pin says $sha" >&2
    exit 1
fi
# A changed pin writes its version beside any older one; Gradle resolves only the pinned version.
dir="$DEST/repo/dev/windowcast/windowcast-android/$version"
mkdir -p "$dir"
cp "$work/$aar" "$dir/"
# The AAR has no dependencies of its own beyond the Kotlin standard library, which droidtop already has.
cat > "$dir/windowcast-android-$version.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.windowcast</groupId>
  <artifactId>windowcast-android</artifactId>
  <version>$version</version>
  <packaging>aar</packaging>
</project>
POM
echo "$tag $sha" > "$DEST/.pin"
find "$DEST" -maxdepth 8 -type f
