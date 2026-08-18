// Package api is the local HTTP server the DroidTop Android app talks to
// over the LAN — this is what "PC-side helper" means concretely: a small
// background service on the gaming PC, not a cloud service.
//
// Security note, not yet fully designed: every endpoint here does something
// consequential (install software, register apps with Sunshine), so this
// cannot ship without pairing/auth — a bearer token issued during a
// one-time pairing step (the helper shows a short code, the user enters it
// in the DroidTop app once) is the intended model, not implemented yet.
// Do not wire this server up to a real network listener without that in
// place first.
package api

import (
	"encoding/json"
	"net/http"

	"github.com/droidtop/pc-helper/internal/steam"
	"github.com/droidtop/pc-helper/internal/sunshine"
)

type Server struct {
	sunshine *sunshine.Client
	// pairingToken string // TODO: issued via a one-time pairing flow; every
	// handler below needs to check it before this is safe to expose on a LAN.
}

func NewServer(sunshineClient *sunshine.Client) *Server {
	return &Server{sunshine: sunshineClient}
}

func (s *Server) Routes() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/apps/install", s.handleInstall)
	mux.HandleFunc("/v1/apps/register-stream", s.handleRegisterStream)
	return mux
}

type installRequest struct {
	SteamAppID int `json:"steamAppId"`
}

// handleInstall is the "user taps Install on their phone" endpoint. See
// steam.TriggerInstallViaProtocol's doc comment: this is best-effort, not
// headless — the Steam client on this PC will come to the foreground.
func (s *Server) handleInstall(w http.ResponseWriter, r *http.Request) {
	var req installRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	if err := steam.TriggerInstallViaProtocol(req.SteamAppID); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusAccepted)
}

type registerStreamRequest struct {
	Name      string `json:"name"`
	Cmd       string `json:"cmd"`
	ImagePath string `json:"imagePath"`
}

// handleRegisterStream is the proven half: add a game to Sunshine's app
// list via its REST API, so it shows up as streamable without the user
// touching Sunshine's web UI at all.
func (s *Server) handleRegisterStream(w http.ResponseWriter, r *http.Request) {
	var req registerStreamRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	err := s.sunshine.AddApp(sunshine.App{
		Name:      req.Name,
		Cmd:       req.Cmd,
		ImagePath: req.ImagePath,
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusOK)
}
