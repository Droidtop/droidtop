#!/bin/bash
# Cross-compiles the native dependencies Gradle's CMake builds can't build
# themselves: libffi + libwayland-client (for :host-bridge) and mbedTLS
# (for :runtime-remote-stream, via moonlight-common-c's USE_MBEDTLS option
# — that one's wired up directly in runtime-remote-stream's CMakeLists.txt
# via add_subdirectory, so it needs no separate step here).
#
# Verified working in a WSL2 Ubuntu 24.04 environment with:
#   apt: build-essential cmake ninja-build meson pkg-config git python3
#        autoconf automake libtool texinfo libexpat1-dev libffi-dev
#        libxml2-utils wayland-protocols libwayland-bin
#   Android SDK/NDK 27.0.12077973 installed via cmdline-tools' sdkmanager
#
# Usage: ./build-vendor-deps.sh [android-abi] [api-level] [ndk-path]
# Defaults match this repo's build.gradle.kts files (arm64-v8a, API 26,
# NDK 27.0.12077973 under $ANDROID_SDK_ROOT/ndk).

set -euo pipefail

ABI="${1:-arm64-v8a}"
API="${2:-26}"
NDK="${3:-${ANDROID_SDK_ROOT:-/opt/android-sdk}/ndk/27.0.12077973}"
DEPS_PREFIX="${ANDROID_DEPS_PREFIX:-/opt/android-deps}"
DEPS_DIR="$DEPS_PREFIX/$ABI"

if [ "$ABI" != "arm64-v8a" ]; then
    echo "Only arm64-v8a's target triple (aarch64-linux-android) is wired up below." >&2
    echo "Add another case if/when a second ABI is actually needed." >&2
    exit 1
fi
TARGET_TRIPLE="aarch64-linux-android"

TOOLCHAIN_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TOOLCHAIN_BIN/$TARGET_TRIPLE$API-clang"
CXX="$TOOLCHAIN_BIN/$TARGET_TRIPLE$API-clang++"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENDOR="$REPO_ROOT/vendor"
WORK="$REPO_ROOT/.vendor-deps-build/$ABI"
mkdir -p "$WORK" "$DEPS_DIR"

echo "=== Stripping CRLF from vendor/ (Windows git checkouts corrupt shell scripts) ==="
find "$VENDOR" -type f -not -path '*/.git/*' -print0 | while IFS= read -r -d '' f; do
    if file "$f" | grep -q "CRLF"; then sed -i 's/\r$//' "$f"; fi
done

echo "=== libffi ($ABI) ==="
# Subshell: CC/CXX/AR/etc. must not leak into the native (x86_64) wayland-
# scanner build below — that bit us once already (meson tried to run an
# Android-targeted "native" sanity-check binary on the x86_64 host and got
# Exec format error, because these exact env vars were still exported).
(
    export PATH="$TOOLCHAIN_BIN:$PATH"
    export CC CXX AR=llvm-ar AS="$CC" LD=ld RANLIB=llvm-ranlib STRIP=llvm-strip
    cd "$VENDOR/libffi"
    if [ ! -f configure ]; then
        # libffi's Makefile.am ships ACLOCAL_AMFLAGS which conflicts with its
        # own AC_CONFIG_MACRO_DIRS([m4]) under current autoconf/libtool —
        # strip it. Known upstream autotools-version friction, not a bug here.
        sed -i '/^ACLOCAL_AMFLAGS = -I m4$/d' Makefile.am
        ./autogen.sh
    fi
    BUILD_DIR="$WORK/libffi"
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"
    "$VENDOR/libffi/configure" \
        --host="$TARGET_TRIPLE" \
        --prefix="$DEPS_DIR" \
        --disable-shared --enable-static --disable-docs
    make -j"$(nproc)"
    make install
)

echo "=== wayland-scanner (native x86_64, matching vendor/wayland's exact version) ==="
NATIVE_PREFIX="$WORK/wayland-native"
(
    cd "$VENDOR/wayland"
    meson setup "$WORK/wayland-native-build" --wipe \
        --prefix="$NATIVE_PREFIX" \
        -Dlibraries=false -Dscanner=true -Ddocumentation=false \
        -Ddtd_validation=false -Dtests=false
    ninja -C "$WORK/wayland-native-build"
    ninja -C "$WORK/wayland-native-build" install
)

echo "=== libwayland-client ($ABI) ==="
CROSS_INI="$WORK/android-cross.ini"
cat > "$CROSS_INI" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$TOOLCHAIN_BIN/llvm-ar'
strip = '$TOOLCHAIN_BIN/llvm-strip'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[properties]
pkg_config_libdir = '$DEPS_DIR/lib/pkgconfig'
EOF

NATIVE_INI="$WORK/android-native.ini"
cat > "$NATIVE_INI" <<EOF
[properties]
pkg_config_libdir = '$NATIVE_PREFIX/lib/x86_64-linux-gnu/pkgconfig'
EOF

(
    cd "$VENDOR/wayland"
    meson setup "$WORK/wayland-android-build" --wipe \
        --cross-file "$CROSS_INI" \
        --native-file "$NATIVE_INI" \
        --prefix="$DEPS_DIR" \
        -Dlibraries=true -Dscanner=false -Ddocumentation=false \
        -Ddtd_validation=false -Dtests=false
    ninja -C "$WORK/wayland-android-build"
    ninja -C "$WORK/wayland-android-build" install
)

echo "=== Copying runtime .so deps into host-bridge's jniLibs (packaged into the APK) ==="
JNILIBS="$REPO_ROOT/host-bridge/src/main/jniLibs/$ABI"
mkdir -p "$JNILIBS"
cp "$DEPS_DIR/lib/libwayland-client.so" "$JNILIBS/"

echo "=== Done. Deps installed under $DEPS_DIR ==="
find "$DEPS_DIR" -iname "*wayland-client*" -o -iname "libffi.a"
