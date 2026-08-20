#!/bin/bash
# Cross-compiles the native dependencies Gradle's CMake builds can't build
# themselves: libffi + libwayland-client (for :host-bridge) and mbedTLS
# (for :runtime-remote-stream, via moonlight-common-c's USE_MBEDTLS option
# — that one's wired up directly in runtime-remote-stream's CMakeLists.txt
# via add_subdirectory, so it needs no separate step here).
#
# Verified working in a WSL2 Ubuntu 24.04 environment with:
#   apt: build-essential cmake ninja-build meson pkg-config git python3
#        autoconf automake libtool libltdl-dev texinfo libexpat1-dev
#        libffi-dev libxml2-utils wayland-protocols libwayland-bin
#   gh (GitHub CLI), authenticated (`gh auth login`) — used to pull the
#        mirrored musl cross toolchains, see the droidspaces section below.
#        Already preinstalled + authenticated via GH_TOKEN on GitHub-hosted
#        Actions runners; needs manual install+login for local use.
#   Android SDK/NDK 27.0.12077973 installed via cmdline-tools' sdkmanager
#
# libltdl-dev matters specifically: it's the package that ships ltdl.m4
# (needed below for libffi's LT_SYS_SYMBOL_USCORE). It was present on the
# WSL2 box only because Debian/Ubuntu installs Recommends by default there
# — GitHub Actions' runner apt config doesn't, so omitting it from an
# explicit install list is a real, CI-only failure, not a hypothetical one
# (this is exactly what happened the first two times this workflow ran).
#
# Usage: ./build-vendor-deps.sh [android-abi] [api-level] [ndk-path]
# Builds ONE ABI per invocation — for a fat/universal APK covering both
# targets (arm64-v8a for real hardware, x86_64 for emulators/x86 devices),
# call this script once per ABI; see the "for ABI in ..." loop in
# .github/workflows/android-build.yml. Defaults match this repo's
# build.gradle.kts files (arm64-v8a, API 26, NDK 27.0.12077973 under
# $ANDROID_SDK_ROOT/ndk).

set -euo pipefail

ABI="${1:-arm64-v8a}"
API="${2:-26}"
NDK="${3:-${ANDROID_SDK_ROOT:-/opt/android-sdk}/ndk/27.0.12077973}"
DEPS_PREFIX="${ANDROID_DEPS_PREFIX:-/opt/android-deps}"
DEPS_DIR="$DEPS_PREFIX/$ABI"

# Per-ABI target triples: one for the NDK's clang (Android/bionic target),
# one for musl.cc's prebuilt cross toolchain (droidspaces' static-musl
# build, unrelated to Android's own libc), and one for Meson's cpu naming
# (which happens to match the musl triple's arch component for both ABIs
# this project targets).
case "$ABI" in
    arm64-v8a)
        TARGET_TRIPLE="aarch64-linux-android"
        MUSL_TRIPLE="aarch64-linux-musl"
        MESON_CPU_FAMILY="aarch64"
        MESON_CPU="aarch64"
        DROIDSPACES_MAKE_TARGET="aarch64"
        ;;
    x86_64)
        TARGET_TRIPLE="x86_64-linux-android"
        MUSL_TRIPLE="x86_64-linux-musl"
        MESON_CPU_FAMILY="x86_64"
        MESON_CPU="x86_64"
        DROIDSPACES_MAKE_TARGET="x86_64"
        ;;
    *)
        echo "No target-triple mapping for ABI '$ABI' — only arm64-v8a and x86_64 are wired up." >&2
        echo "(Those are this project's actual targets: real ARM hardware + x86_64 emulators/devices.)" >&2
        exit 1
        ;;
esac

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

        # `-I m4` passed explicitly (not left to AC_CONFIG_MACRO_DIRS([m4])
        # auto-detection) — needed on some but not all environments.
        #
        # libffi's configure.ac uses LT_SYS_SYMBOL_USCORE, which lives in
        # libtool's ltdl.m4 — a file `libtoolize --copy` does NOT copy into
        # m4/ by default (only libtool.m4/ltoptions.m4/ltsugar.m4/
        # ltversion.m4/lt~obsolete.m4 are). aclocal still finds it via its
        # own system search path in some environments (worked in WSL2/
        # Ubuntu 24.04) but not others (GitHub Actions' ubuntu-24.04 runner
        # failed with "possibly undefined macro: LT_SYS_SYMBOL_USCORE" even
        # with -I m4 correctly applied — confirmed from the actual CI log,
        # not guessed). Copying it into m4/ explicitly removes the
        # dependency on that implicit, apparently-not-actually-portable
        # system search path entirely.
        mkdir -p m4
        ltdl_m4="$(find /usr/share/aclocal* -name ltdl.m4 2>/dev/null | head -1)"
        if [ -n "$ltdl_m4" ]; then
            cp "$ltdl_m4" m4/
        else
            echo "WARNING: ltdl.m4 not found under /usr/share/aclocal* — libffi's autoreconf may fail on LT_SYS_SYMBOL_USCORE" >&2
        fi

        autoreconf -v -i -I m4
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
cpu_family = '$MESON_CPU_FAMILY'
cpu = '$MESON_CPU'
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

echo "=== droidspaces ($ABI) ==="
# Genuinely simple compared to everything above: a single static musl
# binary, no shared-library deps at all (musl's static linking means it
# only needs the Linux kernel syscall ABI, which Android provides — this
# is exactly why it runs fine on Android despite being built against musl,
# not bionic). Just needs a prebuilt musl cross toolchain matching $ABI.
MUSL_TOOLCHAIN_DIR="$REPO_ROOT/.vendor-deps-build/musl-cross-toolchain-$MUSL_TRIPLE"
MUSL_CROSS_BIN="$MUSL_TOOLCHAIN_DIR/$MUSL_TRIPLE-cross/bin"
if [ ! -x "$MUSL_CROSS_BIN/$MUSL_TRIPLE-gcc" ]; then
    mkdir -p "$MUSL_TOOLCHAIN_DIR"
    # musl.cc isn't behind a CDN, and downloading directly from it proved
    # genuinely unreliable from GitHub Actions runners -- not a transient
    # blip: a real run retried 6 times over 12+ minutes (--retry 5,
    # --max-time 120 each) and never got past a connection timeout even
    # once. Mirrored both toolchains as assets on this repo's own
    # "build-toolchains" release instead (re-hosted as-is, not modified —
    # re-pull from https://musl.cc if they ever need updating) and pull
    # from there via `gh release download`, which is authenticated and
    # backed by GitHub's own reliable infrastructure rather than a single
    # third-party host. Needs `gh auth login` locally, or GH_TOKEN set in
    # CI (see .github/workflows/android-build.yml).
    REPO_SLUG="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || echo bi0shacker001/droidtop)"
    gh release download build-toolchains \
        --repo "$REPO_SLUG" \
        --pattern "$MUSL_TRIPLE-cross.tgz" \
        --dir "$MUSL_TOOLCHAIN_DIR" \
        --clobber
    tar -xzf "$MUSL_TOOLCHAIN_DIR/$MUSL_TRIPLE-cross.tgz" -C "$MUSL_TOOLCHAIN_DIR"
    rm "$MUSL_TOOLCHAIN_DIR/$MUSL_TRIPLE-cross.tgz"
fi

(
    export MUSL_CROSS="$MUSL_CROSS_BIN"
    cd "$VENDOR/droidspaces"
    make clean >/dev/null 2>&1 || true
    # Upstream's own CFLAGS (copied from their Makefile) plus five
    # -Wno-error= exceptions for warning classes that are false positives
    # specifically under this musl-cross-make GCC version (confirmed by
    # building it: every one of these fires on generic bounds-checked
    # helpers like `safe_strncpy(dst, size, ...)` where `size` is a runtime
    # parameter GCC's static analysis can't fully resolve, not on any
    # actual bug) — everything else stays -Werror, matching upstream intent.
    make "$DROIDSPACES_MAKE_TARGET" CFLAGS="-Wall -Wextra -Wpedantic -Werror -O2 -flto=auto -std=gnu99 -Isrc/include -no-pie -pthread -Wformat=2 -Wformat-security -Wnull-dereference -Wcast-qual -Wlogical-op -Wshadow -Wdouble-promotion -Wundef -Wduplicated-cond -Wduplicated-branches -Wimplicit-fallthrough=3 -fstack-protector-strong -Wno-error=format-truncation -Wno-error=format-overflow -Wno-error=array-bounds -Wno-error=stringop-truncation -Wno-error=stringop-overflow"
)

echo "=== Copying droidspaces binary into runtime-linux-root's assets (packaged into the APK) ==="
DS_ASSETS="$REPO_ROOT/runtime-linux-root/src/main/assets/bin"
mkdir -p "$DS_ASSETS"
cp "$VENDOR/droidspaces/output/droidspaces" "$DS_ASSETS/droidspaces-$ABI"

echo "=== Done. Deps installed under $DEPS_DIR ==="
find "$DEPS_DIR" -iname "*wayland-client*" -o -iname "libffi.a"
file "$DS_ASSETS/droidspaces-$ABI"
