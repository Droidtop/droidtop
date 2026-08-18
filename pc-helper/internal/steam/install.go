// Package steam triggers a Steam game install from pc-helper. Read this
// file's doc comments before wiring it into anything user-facing — unlike
// package sunshine, NEITHER mechanism here is a genuine unattended install.
// See pc-helper/README.md and docs/SPEC.md for the full honest breakdown;
// this is deliberately not oversold in the API shape below.
package steam

import (
	"fmt"
	"os/exec"
	"runtime"
)

// TriggerInstallViaProtocol opens steam://install/<appID>, which focuses
// the (already-running, already-logged-in) Steam client and generally
// starts that app's install flow. This is NOT headless: it requires Steam
// running and the user authenticated, and it WILL bring the Steam client
// window to the foreground — there is no documented flag for a silent
// variant. Treat this as "the phone tells the PC to start installing, and
// the user may still need to click through a prompt on the PC," not as a
// fire-and-forget remote install.
func TriggerInstallViaProtocol(appID int) error {
	uri := fmt.Sprintf("steam://install/%d", appID)

	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("cmd", "/c", "start", "", uri)
	case "linux":
		cmd = exec.Command("xdg-open", uri)
	default:
		return fmt.Errorf("unsupported OS for steam:// URI trigger: %s", runtime.GOOS)
	}
	return cmd.Run()
}

// TriggerInstallViaSteamCmd is the scripted-install alternative. Real
// constraints, not implementation details to smooth over later:
//
//   - Requires steamcmd.exe/steamcmd.sh present and a ONE-TIME interactive
//     login on THIS machine first, including solving Steam Guard — an
//     automation-hostile step by design. There is no path to "works on
//     first remote request with zero prior setup" here; document that
//     clearly in onboarding rather than implying otherwise.
//   - A naive SteamCmd install does NOT land in the GUI Steam client's
//     normal `steamapps/common/<Game>` layout by default — making it show
//     up as already-installed in the regular client requires pointing
//     force_install_dir at an existing Steam library correctly and getting
//     the resulting appmanifest_<id>.acf right. Fragile across Steam
//     updates; not attempted in this stub.
//
// Given both caveats, this should ship OFF by default, opt-in per user,
// with the onboarding flow explaining the one-time setup cost up front —
// not presented as equivalent to the steam:// path above.
func TriggerInstallViaSteamCmd(steamCmdPath string, appID int, installDir string) error {
	return fmt.Errorf("not implemented — see doc comment for why this needs a one-time per-machine setup step before it can work at all")
}
