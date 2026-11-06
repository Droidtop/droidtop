#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

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
struct ANativeWindow;

namespace hostbridge {

struct WaylandGlobals;
struct OutputCapture;
struct ClipboardState;
struct TextFieldState;
struct DispatchTasks;

/**
 * Called on a hostbridge-owned worker thread (NOT the Wayland dispatch
 * thread) whenever the container's selection changes and its text has been
 * read off the transfer pipe. `utf8Text` is `length` bytes of real UTF-8,
 * valid only for the duration of the call.
 *
 * Length-explicit, and bytes rather than a C string, on purpose: this text
 * crosses into Java, and JNI's NewStringUTF/GetStringUTFChars speak MODIFIED
 * UTF-8, which disagrees with real UTF-8 on every supplementary-plane
 * character — i.e. on emoji, which people copy constantly. hostbridge_jni.cpp
 * therefore moves a byte[] across and lets Kotlin do the decoding.
 *
 * Deliberately a plain function pointer + opaque user data rather than a
 * std::function: the only implementation is hostbridge_jni.cpp's JNI
 * trampoline, and this keeps the header free of <functional>.
 */
using ClipboardTextCallback = void (*)(void* userData, const char* utf8Text, size_t length);

/**
 * Called on the dispatch thread (see wayland_client.cpp) whenever the
 * container-side toplevel list changes — a window opened, closed, or had
 * its title/app-id/activated/minimized/maximized state change. Carries no
 * data: the callee (hostbridge_jni.cpp's trampoline) is expected to call
 * back into snapshotToplevels() for the current list, the same
 * fetch-on-notify shape as everything else this class exposes to Kotlin.
 */
using ToplevelsChangedCallback = void (*)(void* userData);

// A text field in the container took (true) or lost (false) the keyboard
// focus, as the compositor tells its input method (input-method-unstable-v2).
// Called on the dispatch thread. See setTextFieldListener.
using TextFieldCallback = void (*)(void* userData, bool focused);

/**
 * One opened window as wlr-foreign-toplevel-management-unstable-v1 reports
 * it. `id` is the zwlr_foreign_toplevel_handle_v1 pointer reinterpreted as
 * an integer — stable for the handle's lifetime, which is exactly the
 * lifetime a taskbar row needs it for, and never reused while that handle
 * is still alive (a fresh object gets a fresh pointer).
 */
struct ToplevelInfo {
    uint64_t id = 0;
    std::string title;
    std::string appId;
    bool activated = false;
    bool minimized = false;
    bool maximized = false;
    bool fullscreen = false;
    // The compositor's name for the output the window is listed on (Droidtop/tracker#908, step D4): the one it
    // entered last among those it is on, or the one it was on last when it is on none (a window sway hid in its
    // scratchpad); "" when there is none or the output gave no name. output_match.h's windowOutput is the rule.
    std::string output;
};

/**
 * One compositor output's place in the layout, as wlr-output-management
 * last reported it (at its `done`, when a head's state is whole). The
 * output's box in the layout follows from it the way wlroots works it out
 * (wlr_output_layout.c: the position, and the current mode transformed and
 * divided by the scale); host-bridge's Kotlin side does that sum
 * (OutputLayout.kt) so a pointer on a named output lands on it.
 */
struct OutputPlacement {
    std::string name;
    bool enabled = false;
    int32_t x = 0;            // logical pixels, the head's `position`
    int32_t y = 0;
    int32_t modeWidth = 0;    // the current mode, in pixels; 0 while unknown
    int32_t modeHeight = 0;
    int32_t transform = 0;    // wl_output.transform
    double scale = 1.0;
};

class WaylandClient {
public:
    ~WaylandClient();

    // Connects to a Wayland compositor listening on a UNIX socket at
    // `socketPath` (a path INTO the primary container's filesystem/mount
    // namespace — not a bare $WAYLAND_DISPLAY name, since this process is
    // outside that container's namespace and must reach the socket file
    // directly). Returns false on any failure (socket open, wl_display
    // connect, or required globals missing from the registry). Starts a
    // background dispatch thread on success (see .cpp — this is what makes
    // async screencopy frame delivery work without the caller polling).
    bool connect(const char* socketPath);

    void disconnect();

    bool isConnected() const { return display_ != nullptr; }

    // True once the dispatch thread has seen the connection fail: the
    // compositor exited or closed the socket (Droidtop/tracker#688). Set
    // from the dispatch thread, read from any thread; disconnect() does not
    // set it, so a connection droidtop closed itself never reads as lost.
    bool connectionLost() const { return connectionLost_; }

    // ---- Outputs (Droidtop/tracker#908) ----
    //
    // Every wl_output the compositor advertises is tracked, in bind order.
    // [outputName] picks one by the name its wl_output v4 `name` event gave
    // ("HEADLESS-1" under sway); "" means the primary, the first bound,
    // which is the one output host-bridge knew before it tracked several
    // (output_match.h holds the rule).

    // The names of the outputs that have one, primary first. Any thread.
    std::vector<std::string> outputNames();

    // Starts (or restarts, if already presenting) a continuous screencopy
    // capture loop of that output into `window`. Each output has at most
    // one capture; presenting an output again replaces its old one.
    // `window` must stay valid until that capture stops (stopPresenting,
    // a later presentOutput of the same output, the output going away, or
    // disconnect()); the caller (hostbridge_jni.cpp) owns its lifetime via
    // ANativeWindow_fromSurface/release around the JNI call. Returns the
    // output's id (its registry name, never 0) or 0 when nothing was
    // started, so the caller can key the window by the output it really
    // went to.
    uint32_t presentOutput(const std::string& outputName, ANativeWindow* window);

    // Stops that output's capture. Returns the id presentOutput gave for it,
    // or 0 when it had none; after it returns its window is no longer drawn
    // into and may be released.
    uint32_t stopPresenting(const std::string& outputName);

    // Stops every output's capture (disconnect()).
    void stopAllPresenting();

    // Asks the compositor, over wlr-output-management, to give that output
    // this exact size (a custom mode: a headless output accepts any). The
    // Android view showing the desktop calls this with its own size, so a
    // captured frame maps 1:1 onto the view instead of being stretched.
    // Kept and applied as soon as the compositor has announced the output's
    // head (the head of the same name; the primary falls back to the first
    // head); returns false only when it offers no output management at all.
    // [scale] is the output's scale (logical pixel to physical pixels); 0
    // leaves the compositor's own.
    bool setOutputSize(const std::string& outputName, int32_t width, int32_t height, double scale);

    // Every output-management head's place in the layout, in the order the
    // compositor announced the heads; empty without output management. A
    // copy, safe from any thread: it is the one piece of output state kept
    // for other threads, so pointer input does not wait on the dispatch
    // thread for each motion.
    std::vector<OutputPlacement> outputPlacements();

    // Input injection — safe to call from a thread other than the one that
    // called connect()/runs the dispatch loop. libwayland-client's requests
    // (proxy marshaling) are documented thread-safe independent of a
    // concurrent dispatch thread, AS LONG AS dispatch itself is only ever
    // called from one thread — which is exactly this class's design (see
    // .cpp's dispatch thread). No additional locking needed here for that
    // reason; don't add a mutex "just in case" without re-reading that
    // guarantee first.
    void injectPointerMotion(double dx, double dy);
    void injectPointerMotionAbsolute(double x, double y, uint32_t extentWidth, uint32_t extentHeight);
    void injectPointerButton(uint32_t linuxButtonCode, bool pressed);
    void injectPointerAxis(double horizontal, double vertical);
    // [typed]: the key was derived from a character (an on-screen keyboard), so it goes through the
    // US-keymap text keyboard; otherwise through the layout keyboard (docs/SPEC.md 6b "Keyboard layout").
    void injectKey(uint32_t evdevKeyCode, bool pressed, bool typed);
    // The modifier state of the same keyboard [typed] names, as zwp_virtual_keyboard_v1.modifiers takes it.
    // wlroots does not derive it from key events (update_state = false in wlr_virtual_keyboard_v1.c), so
    // HostBridge.kt tracks it per keyboard and sends it after each key that changes it (docs/SPEC.md 6b).
    void injectModifiers(uint32_t depressed, uint32_t latched, uint32_t locked, uint32_t group, bool typed);

    // Replaces the layout keyboard's XKB keymap with [keymap] (XKB text, [length] bytes, no NUL needed).
    bool setKeymap(const char* keymap, size_t length);

    // ---- Windows, over wlr-foreign-toplevel-management-unstable-v1 ----
    //
    // Non-fatal when the compositor doesn't advertise this global (like the
    // clipboard's ext-data-control-v1 below): the desktop's own single
    // presented surface still works, the taskbar just has no window list to
    // show. Registers the sink the same way setClipboardListener does —
    // before connect() is not required here since the manager delivers the
    // FULL current toplevel list as a burst of events right after binding,
    // and a listener set any time before the first snapshotToplevels() call
    // sees it.
    void setToplevelsListener(ToplevelsChangedCallback callback, void* userData);

    // ---- Text fields, over input-method-unstable-v2 (Droidtop/tracker#685) ----
    //
    // host-bridge registers as the seat's input method, which is how a
    // compositor tells a keyboard that a text field (a text-input-v3 client:
    // GTK, Qt, foot) has the focus. Only that is used: typing still goes
    // through the virtual keyboards. Optional, like the toplevel manager: a
    // compositor without it, or one whose seat already has an input method
    // (the protocol's `unavailable`), simply never calls back. Set before
    // connect().
    void setTextFieldListener(TextFieldCallback callback, void* userData);

    // Whether droidtop holds the seat's one input method (Droidtop/tracker#809). Released, a container's own input
    // method (fcitx5, ibus) can take the seat, and text fields no longer show the keyboard. Any thread; applies at
    // once on a connection, else from the next connect().
    void setInputMethodTaken(bool taken);

    // A copy of the current list, safe to call from any thread. Entries are
    // only ever mutated on the dispatch thread; reads take the same mutex
    // that guards those mutations (see ToplevelState in the .cpp).
    std::vector<ToplevelInfo> snapshotToplevels();

    // Requests below run on the dispatch thread (like setOutputSize) rather
    // than marshaling the Wayland request directly from the calling thread:
    // the handle they act on can be destroyed by a `closed` event arriving
    // on the dispatch thread at any time, and only that thread is allowed to
    // decide a given id is still live. Each returns false when `id` no
    // longer names a live toplevel (already closed, or never existed) or
    // when the compositor offers no foreign-toplevel-management at all.
    bool activateToplevel(uint64_t id);
    bool setToplevelMinimized(uint64_t id, bool minimized);
    bool closeToplevel(uint64_t id);

    // ---- Clipboard, over ext-data-control-v1 ----
    //
    // Registers the sink for container -> Android text. Must be set before
    // connect() to be sure of catching the selection the compositor already
    // holds; setting it later only catches subsequent changes. Passing
    // nullptr detaches.
    void setClipboardListener(ClipboardTextCallback callback, void* userData);

    // Android -> container: claims the container seat's selection with
    // `length` bytes of UTF-8 as a text/plain payload. Returns false if the
    // compositor never advertised ext_data_control_manager_v1 (so nothing was
    // claimed), or if the text exceeds kMaxClipboardBytes. Safe to call from
    // any thread, for the same libwayland reason as the injection methods
    // above.
    bool offerClipboardText(const char* utf8Text, size_t length);

    // Both directions refuse payloads above this. A clipboard bridge is for
    // text a person copied, not a transport: an unbounded payload here would
    // be copied through a pipe, a JNI string and an Android ClipData, and a
    // runaway one would be invisible to the user who triggered it.
    static constexpr size_t kMaxClipboardBytes = 1u << 20; // 1 MiB

    // Public only so the free-function pthread trampoline in
    // wayland_client.cpp (pthread_create needs a plain function pointer,
    // not a bound member function) can call it — not part of the intended
    // external API otherwise. Don't call this from outside the dispatch
    // thread it's designed to run on.
    void dispatchLoop();

private:
    struct wl_display* display_ = nullptr;
    struct wl_registry* registry_ = nullptr;
    WaylandGlobals* globals_ = nullptr;
    ClipboardState* clipboard_ = nullptr;
    struct ToplevelState* toplevels_ = nullptr;
    struct TextFieldState* textField_ = nullptr;

    void* dispatchThreadHandle_ = nullptr; // pthread_t, opaque here to avoid pulling <pthread.h> into the header
    std::atomic<bool> dispatchThreadRunning_{false};
    std::atomic<bool> connectionLost_{false};

    // Wakes the dispatch thread out of poll(): posted tasks, shutdown.
    int wakeFd_ = -1;
    DispatchTasks* tasks_ = nullptr;

    void startDispatchThread();
    void stopDispatchThread();

    // Runs `task` on the dispatch thread and waits for it: everything that
    // touches capture or output-configuration state goes through here, so
    // that state has exactly one thread. Runs inline when there is no
    // dispatch thread (before connect() finishes, after it has exited).
    void runOnDispatchThread(void (*task)(WaylandClient*, void*), void* arg);
    void runPendingTasks();
    void wake();
};

} // namespace hostbridge
