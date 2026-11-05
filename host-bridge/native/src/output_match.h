#pragma once

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

} // namespace hostbridge
