# Termux package recipe for Mesa's Venus Vulkan driver (libvulkan_virtio.so), for droidtop's x86_64 Wine guest
# (docs/SPEC.md 10b "The x86_64 Windows runtime", Droidtop/tracker#608). The x86_64 graphics workflow adds it to
# termux-packages as packages/mesa-venus and builds it with Termux's package builder. Its source, vendor/mesa-venus,
# carries Termux's Mesa patches except 0005-virgl-socket-path, which droidtop's own 0005 replaces: the socket of the
# app's vtest server comes from VTEST_SOCKET_NAME, not from a path under Termux's prefix.
#
# Venus forwards every Vulkan call over that socket to a vtest server (virglrenderer with Venus), which replays it
# on Android's own Vulkan, the host GPU on an emulator or BlueStacks. Only the virtio Vulkan driver and its X11 WSI
# are built; no OpenGL, no gallium driver, no LLVM.
TERMUX_PKG_HOMEPAGE=https://www.mesa3d.org
TERMUX_PKG_DESCRIPTION="Mesa's Venus (virtio) Vulkan driver for droidtop's x86_64 Wine guest"
TERMUX_PKG_LICENSE="MIT"
TERMUX_PKG_LICENSE_FILE="docs/license.rst"
TERMUX_PKG_MAINTAINER="droidtop"
TERMUX_PKG_VERSION="26.2.4"
TERMUX_PKG_DEPENDS="libandroid-shmem, libc++, libdrm, libx11, libxcb, libxshmfence, zlib, zstd"
TERMUX_PKG_BUILD_DEPENDS="xorgproto"

TERMUX_PKG_EXTRA_CONFIGURE_ARGS="
-Dplatforms=x11
-Dglx=disabled
-Dglvnd=disabled
-Dopengl=false
-Degl=disabled
-Dgbm=disabled
-Dgles1=disabled
-Dgles2=disabled
-Dllvm=disabled
-Dgallium-drivers=
-Dvulkan-drivers=virtio
-Dgallium-rusticl=false
-Dxlib-lease=disabled
-Dxmlconfig=disabled
-Dvalgrind=disabled
-Dlibunwind=disabled
-Dlmsensors=disabled
-Dbuild-tests=false
"

# The source is vendor/mesa-venus (Droidtop/mesa, branch droidtop-venus): Mesa 26.2.4 with Termux's Mesa patches (all but 0005) and droidtop's 0005 as commits, never patched here
# (Droidtop/tracker#921). The workflow copies that tree into the termux-packages checkout as droidtop-src/mesa-venus, the
# only place the builder's container can read it.
termux_step_get_source() {
	mkdir -p "$TERMUX_PKG_SRCDIR"
	cp -a "$TERMUX_SCRIPTDIR/droidtop-src/mesa-venus/." "$TERMUX_PKG_SRCDIR/"
}

termux_step_post_get_source() {
	# Do not use meson wrap projects
	rm -rf subprojects
}

termux_step_pre_configure() {
	CPPFLAGS+=" -D__USE_GNU"
	LDFLAGS+=" -landroid-shmem"
	LDFLAGS+=" -Wl,--undefined-version"
}
