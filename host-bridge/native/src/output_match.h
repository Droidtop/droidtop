#pragma once

#include <algorithm>
#include <string>
#include <vector>

// Which compositor output a request means, kept free of Wayland types so the
// rule reads on its own. "" always means the primary output: the first
// wl_output bound, which is the one output host-bridge presented before it
// tracked more than one (Droidtop/tracker#908). A name means the output
// whose wl_output `name` event (wl_output v4) said it, "HEADLESS-1" under
// sway.

namespace hostbridge {

// The index in [outputNames] (bind order; "" for an output that never said
// its name) of the output [wanted] means, or -1 when none is.
inline int findOutputIndex(const std::string& wanted, const std::vector<std::string>& outputNames) {
    if (wanted.empty()) return outputNames.empty() ? -1 : 0;
    for (size_t i = 0; i < outputNames.size(); i++) {
        if (outputNames[i] == wanted) return static_cast<int>(i);
    }
    return -1;
}

// The index in [headNames] (wlr-output-management heads in the order the
// compositor announced them) of the head [wanted] means, or -1. [primaryName]
// is the primary output's name, "" when it gave none (wl_output below v4).
// The primary's head is the head of its name; with no name to match, or no
// head of that name, it is the first head, which is what host-bridge sized
// while it knew one output only. A named output has only the head of its
// own name: wlroots gives an output and its head the same name.
inline int findHeadIndex(const std::string& wanted, const std::string& primaryName,
                         const std::vector<std::string>& headNames) {
    const std::string& target = wanted.empty() ? primaryName : wanted;
    if (!target.empty()) {
        for (size_t i = 0; i < headNames.size(); i++) {
            if (headNames[i] == target) return static_cast<int>(i);
        }
    }
    if (wanted.empty()) return headNames.empty() ? -1 : 0;
    return -1;
}

// Which output a window is listed on (Droidtop/tracker#908, step D4), from
// wlr-foreign-toplevel-management's output_enter and output_leave, kept free
// of Wayland types like the rules above. [on] is the outputs the window is
// on now, in the order it entered them; [last] the output it was on most
// recently, kept after it left every output (a window sway hides in its
// scratchpad leaves its output, and stays listed on the bar it was on). An
// Output{} is "none".
//
// A window on two outputs at once (dragged across the edge) is listed on the
// one it entered last: the protocol says which outputs a window is on, not
// how much of it is on each, so "the one holding most of it" is not known.
template <typename Output>
inline void windowEntered(std::vector<Output>& on, Output& last, Output output) {
    on.erase(std::remove(on.begin(), on.end(), output), on.end());
    on.push_back(output);
    last = output;
}

template <typename Output>
inline void windowLeft(std::vector<Output>& on, Output& last, Output output) {
    on.erase(std::remove(on.begin(), on.end(), output), on.end());
    last = on.empty() ? output : on.back();
}

// The output went away (the global was removed): no window is on it, and none
// is listed by it any more.
template <typename Output>
inline void outputGone(std::vector<Output>& on, Output& last, Output output) {
    on.erase(std::remove(on.begin(), on.end(), output), on.end());
    if (last == output) last = on.empty() ? Output{} : on.back();
}

// The output the window is listed on: the one it entered last among those it
// is on, or, on none, the one it was on last; Output{} when there is none.
template <typename Output>
inline Output windowOutput(const std::vector<Output>& on, Output last) {
    return on.empty() ? last : on.back();
}

} // namespace hostbridge
