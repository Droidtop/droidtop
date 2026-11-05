# Termux package recipe for Mesa's Venus Vulkan driver (libvulkan_virtio.so), for droidtop's x86_64 Wine guest
# (docs/SPEC.md 10b "The x86_64 Windows runtime", Droidtop/tracker#608). prepare.sh adds it to termux-packages as
# packages/mesa-venus, beside Termux's own packages/mesa, with that recipe's patches except
# 0005-virgl-socket-path.patch, which droidtop's 0005-vtest-socket-from-env.patch replaces: the socket of the
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
TERMUX_PKG_SRCURL="https://archive.mesa3d.org/mesa-${TERMUX_PKG_VERSION}.tar.xz"
TERMUX_PKG_SHA256=bce5f7fbebb934373b86c999a064d52fb5065878dc57f287f95346648ec832e9
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

termux_step_post_get_source() {
	# Do not use meson wrap projects
	rm -rf subprojects
}

termux_step_pre_configure() {
	CPPFLAGS+=" -D__USE_GNU"
	LDFLAGS+=" -landroid-shmem"
	LDFLAGS+=" -Wl,--undefined-version"
}
