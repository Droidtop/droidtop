// Package sunshine wraps Sunshine's local REST API (default host
// https://127.0.0.1:47990, self-signed cert). This is the proven,
// documented half of pc-helper — see docs/SPEC.md's remote-stream section
// and pc-helper/README.md for why the Steam-install half (package steam)
// carries much heavier caveats than this one does.
//
// API reference: https://docs.lizardbyte.dev (Sunshine docs), source at
// LizardByte/Sunshine's docs/api.md. Authenticated via HTTP Basic Auth using
// the same admin username/password that protects Sunshine's web UI.
package sunshine

import (
	"bytes"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net/http"
)

type Client struct {
	baseURL  string // e.g. "https://127.0.0.1:47990"
	username string
	password string
	http     *http.Client
}

func NewClient(baseURL, username, password string) *Client {
	return &Client{
		baseURL:  baseURL,
		username: username,
		password: password,
		// Sunshine's cert is self-signed by design (it's a local admin API,
		// not internet-facing) — InsecureSkipVerify is the accepted
		// tradeoff every Sunshine API client makes for this endpoint.
		http: &http.Client{Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		}},
	}
}

// App mirrors Sunshine's app JSON schema. Confirm field names against the
// live docs/source before relying on this — Sunshine is a fast-moving
// project and the schema isn't guaranteed stable across versions; this was
// written from research, not verified against a running Sunshine instance.
type App struct {
	Index     int    `json:"index"` // -1 to create a new entry
	Name      string `json:"name"`
	Cmd       string `json:"cmd"`
	ImagePath string `json:"image-path,omitempty"`
	Elevated  bool   `json:"elevated,omitempty"`
}

// AddApp registers a newly-installed game with Sunshine so it becomes
// streamable — this is the concrete answer to "auto-add to GameStream" from
// the product ask: no manual apps.json editing, no web UI interaction.
func (c *Client) AddApp(app App) error {
	app.Index = -1
	body, err := json.Marshal(app)
	if err != nil {
		return err
	}

	req, err := http.NewRequest(http.MethodPost, c.baseURL+"/api/apps", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.SetBasicAuth(c.username, c.password)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("sunshine POST /api/apps: unexpected status %d", resp.StatusCode)
	}
	return nil
}

// SubmitPin completes pairing with a DroidTop client via Sunshine's own API
// (POST /api/pin) instead of requiring the user to open Sunshine's web UI —
// this is what lets pairing happen entirely from the DroidTop app's side.
func (c *Client) SubmitPin(pin, deviceName string) error {
	body, _ := json.Marshal(map[string]string{"pin": pin, "name": deviceName})

	req, err := http.NewRequest(http.MethodPost, c.baseURL+"/api/pin", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.SetBasicAuth(c.username, c.password)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("sunshine POST /api/pin: unexpected status %d", resp.StatusCode)
	}
	return nil
}
