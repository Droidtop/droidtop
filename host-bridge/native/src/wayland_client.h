#pragma once

// Thin C++ wrapper around a wl_display connection to the primary container's
// compositor, plus the registry globals hostbridge needs. See wayland_client.cpp
// for what's implemented vs. TODO.

// Forward-declared in the GLOBAL namespace deliberately: using `struct
// wl_display*` etc. directly inside `namespace hostbridge` below, without
// this, silently declares a NEW, distinct `hostbridge::wl_display` type
// (elaborated-type-specifier lookup rules) instead of referring to the real
// one from <wayland-client.h> — caught when wl_display_roundtrip() calls in
// wayland_client.cpp failed to compile against "an incomplete type
// hostbridge::wl_display", not the real wl_display.
struct wl_display;
struct wl_registry;

namespace hostbridge {

struct WaylandGlobals;

class WaylandClient {
public:
    ~WaylandClient();

    // Connects to a Wayland compositor listening on a UNIX socket at
    // `socketPath` (a path INTO the primary container's filesystem/mount
    // namespace — not a bare $WAYLAND_DISPLAY name, since this process is
    // outside that container's namespace and must reach the socket file
    // directly). Returns false on any failure (socket open, wl_display
    // connect, or required globals missing from the registry).
    bool connect(const char* socketPath);

    void disconnect();

    bool isConnected() const { return display_ != nullptr; }

private:
    struct wl_display* display_ = nullptr;
    struct wl_registry* registry_ = nullptr;
    WaylandGlobals* globals_ = nullptr;
};

} // namespace hostbridge
