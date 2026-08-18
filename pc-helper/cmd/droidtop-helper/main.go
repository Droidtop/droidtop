// droidtop-helper is a small background service for the user's gaming PC.
// See pc-helper/README.md before treating this as more than a scaffold —
// it has no pairing/auth yet (see internal/api's doc comment) and should
// not be run exposed on a real network as-is.
package main

import (
	"flag"
	"log"
	"net/http"

	"github.com/droidtop/pc-helper/internal/api"
	"github.com/droidtop/pc-helper/internal/sunshine"
)

func main() {
	listenAddr := flag.String("listen", "127.0.0.1:47991", "address to listen on (LAN-facing once pairing/auth exists)")
	sunshineURL := flag.String("sunshine-url", "https://127.0.0.1:47990", "Sunshine's local API base URL")
	sunshineUser := flag.String("sunshine-user", "", "Sunshine admin username")
	sunshinePass := flag.String("sunshine-pass", "", "Sunshine admin password")
	flag.Parse()

	if *sunshineUser == "" || *sunshinePass == "" {
		log.Fatal("sunshine-user and sunshine-pass are required — droidtop-helper needs Sunshine's admin credentials to call its API")
	}

	client := sunshine.NewClient(*sunshineURL, *sunshineUser, *sunshinePass)
	server := api.NewServer(client)

	log.Printf("droidtop-helper listening on %s (127.0.0.1 only until pairing/auth lands — see README)", *listenAddr)
	log.Fatal(http.ListenAndServe(*listenAddr, server.Routes()))
}
