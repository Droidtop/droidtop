//! Many handhelds and many computers, paired in a partial mesh (owner,
//! 2026-10-10: "if we have 5 droidtop devices and 5 desktops, we should be
//! able to connect and pair any number of permutations of these devices and
//! have them work"). Three handhelds call the same JSON surface droidtop
//! calls; three computers serve the core's protocol over loopback, each
//! taking only the handhelds paired with it:
//!
//! ```text
//!   H1 ── C1      H1 ── C2 ── H2 ── C3 ── H3
//! ```
//!
//! A fourth computer, PC-A, is the real agent (`droidtop_agent_service::Agent`
//! over a game folder), paired with H1 and H3: it covers what the mocks cannot,
//! the same game under the same key on a handheld and a computer, and the
//! person's marks surviving the agent's restarts and rescans.
//!
//! It covers the library relaying across the mesh (one entry per game, with
//! an install per device, and the person's organization of it), saves edited on two devices at once (the newest
//! copy wins and the other device keeps its own), a preferred computer
//! beating a newer copy, and saves reaching a handheld through a computer it
//! shares with another.

use std::fs;
use std::net::{SocketAddr, TcpListener, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, UNIX_EPOCH};

use droidtop_agent::api::call;
use droidtop_agent_core::channel::Channel;
use droidtop_agent_core::context::{AdapterOffer, RecordChange, Records};
use droidtop_agent_core::keys::{DeviceKey, PeerId};
use droidtop_agent_core::library::{Change, Install, Library, ScannedGame};
use droidtop_agent_core::moved::Moved;
use droidtop_agent_core::proto::GameRef;
use droidtop_agent_core::saves::{Roots, SaveSpec};
use droidtop_agent_core::savesync::copy_dir;
use droidtop_agent_core::server::{serve, serve_moved, Host};
use droidtop_agent_core::tunnel;
use droidtop_agent_service::Agent;
use droidtop_agent_store::Dirs;
use serde_json::{json, Value};

fn temp(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("dta-mesh-{}-{name}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

/// A computer: its saves under its own folder, its library, its name.
struct Computer {
    name: String,
    dir: PathBuf,
    library: Mutex<Library>,
    /// The next read of a context's copy is followed by a publish from the
    /// plugin here, so the handheld's put is built on an old generation.
    publish_after_read: Mutex<Option<Records>>,
}

impl Computer {
    fn appdata(&self) -> PathBuf {
        self.dir.join("AppData/Roaming")
    }
    fn save(&self) -> PathBuf {
        self.appdata().join("Game/slot.sav")
    }
}

impl Host for Computer {
    fn name(&self) -> String {
        self.name.clone()
    }
    fn offers(&self, _peer: &PeerId) -> droidtop_agent_core::proto::Offer {
        droidtop_agent_core::proto::Offer::LIBRARY_AND_SAVES
    }
    fn syncs_contexts(&self, _peer: &PeerId) -> bool {
        true
    }
    fn saves(&self, game: &GameRef) -> Option<(SaveSpec, Roots)> {
        (game.key == "steam:1")
            .then(|| (SaveSpec { patterns: vec!["<winAppData>/Game".into()] }, Roots::from([("<winAppData>".into(), self.appdata())])))
    }
    fn archive_dir(&self, _game: &GameRef) -> PathBuf {
        self.dir.join("archive")
    }
    fn library_pull(&self, _peer: &PeerId, since: u64) -> droidtop_agent_core::Result<(Vec<Change>, u64)> {
        Ok(self.library.lock().unwrap().changes_since(since))
    }
    fn library_push(&self, _peer: &PeerId, changes: Vec<Change>) -> droidtop_agent_core::Result<()> {
        let mut lib = self.library.lock().unwrap();
        changes.into_iter().for_each(|c| {
            lib.apply(c);
        });
        Ok(())
    }
    fn context_copy(&self, context: &str) -> droidtop_agent_core::Result<droidtop_agent_core::copy::Copy> {
        let store = droidtop_agent_core::copy::CopyStore::open(&self.dir.join("adapters"), context)?;
        let read = store.current()?;
        if let Some(records) = self.publish_after_read.lock().unwrap().take() {
            store.publish(records, Vec::new())?;
        }
        Ok(read)
    }
    fn context_copy_put(
        &self,
        context: &str,
        base: u64,
        records: Records,
        files: Vec<droidtop_agent_core::copy::CopyFile>,
    ) -> droidtop_agent_core::Result<(u64, Option<String>)> {
        Ok((droidtop_agent_core::copy::CopyStore::open(&self.dir.join("adapters"), context)?.replace(base, records, files)?, None))
    }
    fn context_pull(&self, _context: &str, _offer: Option<&AdapterOffer>) -> droidtop_agent_core::Result<Records> {
        Ok(Records::new())
    }
    fn context_push(&self, _context: &str, _changes: Vec<RecordChange>) -> droidtop_agent_core::Result<Option<String>> {
        Ok(None)
    }
}

/// Starts [`computer`] serving the handhelds in [`paired`]; returns its id and address.
fn start(name: &str, games: &[&str], paired: Vec<PeerId>) -> (&'static Computer, PeerId, SocketAddr) {
    let key = DeviceKey::generate();
    let id = key.peer_id();
    let mut library = Library::default();
    let scanned = games
        .iter()
        .map(|g| ScannedGame {
            key: g.to_string(),
            title: g.to_string(),
            platform: Some("pc".into()),
            install: Install { installed: true, version: Some(format!("{name} build")), ..Default::default() },
        })
        .collect();
    library.update_device(&id, name, scanned);
    let computer: &'static Computer = Box::leak(Box::new(Computer {
        name: name.into(),
        dir: temp(name),
        library: Mutex::new(library),
        publish_after_read: Mutex::new(None),
    }));
    fs::create_dir_all(computer.appdata().join("Game")).unwrap();
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let at = listener.local_addr().unwrap();
    thread::spawn(move || {
        for stream in listener.incoming().flatten() {
            let (key, paired) = (DeviceKey::from_seed(&key.seed()).unwrap(), paired.clone());
            thread::spawn(move || {
                if let Ok(mut ch) = Channel::accept(stream, &key, |p| paired.contains(p)) {
                    let _ = serve(&mut ch, computer);
                }
            });
        }
    });
    (computer, id, at)
}

/// A computer that is the real agent over a game folder, serving the handhelds
/// in [`paired`]. Returns it, its id and address, and the places it keeps its
/// state in (to open the agent again, as a restart does).
fn start_agent(name: &str, games: &Path, paired: Vec<PeerId>) -> ((&'static Agent, PeerId, SocketAddr), Dirs) {
    let root = temp(name);
    let dirs = Dirs { config: root.join("config"), data: root.join("data"), computer: root.join("computer") };
    for d in [&dirs.config, &dirs.data, &dirs.computer] {
        fs::create_dir_all(d).unwrap();
    }
    let agent: &'static Agent = Box::leak(Box::new(Agent::open_in(dirs.clone()).unwrap()));
    {
        let mut settings = agent.store.settings.lock().unwrap();
        settings.name = Some(name.into());
        settings.game_folders = vec![games.to_path_buf()];
    }
    agent.store.save_settings().unwrap();
    // Paired as handhelds, so the agent offers them its library and saves.
    for peer in &paired {
        agent.store.add_device(*peer, &peer.to_hex()[..8], droidtop_agent_core::pairing::Kind::Handheld).unwrap();
    }
    let seed = agent.store.key.seed();
    let id = agent.store.peer_id();
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let at = listener.local_addr().unwrap();
    thread::spawn(move || {
        for stream in listener.incoming().flatten() {
            let (key, paired) = (DeviceKey::from_seed(&seed).unwrap(), paired.clone());
            thread::spawn(move || {
                if let Ok(mut ch) = Channel::accept(stream, &key, |p| paired.contains(p)) {
                    let _ = serve(&mut ch, agent);
                }
            });
        }
    });
    ((agent, id, at), dirs)
}

/// A handheld: its key, its saves folder and its library file.
struct Handheld {
    name: &'static str,
    seed: Value,
    id: PeerId,
    dir: PathBuf,
}

impl Handheld {
    fn new(name: &'static str) -> Handheld {
        let made: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        Handheld { name, id: PeerId::from_hex(made["id"].as_str().unwrap()).unwrap(), seed: made["seed"].clone(), dir: temp(name) }
    }
    fn appdata(&self) -> PathBuf {
        self.dir.join("AppData/Roaming")
    }
    fn save(&self) -> PathBuf {
        self.appdata().join("Game/slot.sav")
    }
    fn args<C: Host + ?Sized>(&self, pc: &(&'static C, PeerId, SocketAddr)) -> Value {
        json!({ "seed": self.seed, "peer": pc.1.to_hex(), "addresses": [pc.2.to_string()], "name": self.name })
    }
    fn library<C: Host + ?Sized>(&self, pc: &(&'static C, PeerId, SocketAddr)) -> Value {
        self.library_with(pc, json!({}))
    }
    /// A library exchange reporting [`marks`], the person's organization of
    /// games as droidtop keeps it.
    fn library_with<C: Host + ?Sized>(&self, pc: &(&'static C, PeerId, SocketAddr), marks: Value) -> Value {
        self.library_scanning(pc, json!([]), marks)
    }
    /// A library exchange reporting the games [`scan`] lists as installed here, and [`marks`].
    fn library_scanning<C: Host + ?Sized>(&self, pc: &(&'static C, PeerId, SocketAddr), scan: Value, marks: Value) -> Value {
        let mut args = self.args(pc);
        args["state"] = json!(self.dir.join("library.json"));
        args["scan"] = scan;
        args["marks"] = marks;
        let out: Value = serde_json::from_str(&call("sync_library", &args.to_string())).unwrap();
        assert!(out.get("error").is_none() && out.get("unreachable").is_none(), "{} with {}: {out}", self.name, pc.0.name());
        out
    }
    /// One saves sync; `choice` is the person's answer to a conflict (`here` or `there`).
    /// A context synced by copies (adapter protocol 2).
    fn context_copy<C: Host + ?Sized>(&self, pc: &(&'static C, PeerId, SocketAddr), device: Value, baseline: Value) -> Value {
        let mut args = self.args(pc);
        args["decl"] = json!({
            "id": "f95checker",
            "presence": "both",
            "fields": [
                { "name": "installed", "direction": "both", "rule": "device" },
                { "name": "version", "direction": "to_device", "rule": "computer" },
            ],
        });
        args["device"] = device;
        args["baseline"] = baseline;
        let out: Value = serde_json::from_str(&call("sync_context_copy", &args.to_string())).unwrap();
        assert!(out.get("error").is_none() && out.get("unreachable").is_none(), "{} with {}: {out}", self.name, pc.0.name());
        out
    }
    fn saves<C: Host + ?Sized>(&self, pc: &(&'static C, PeerId, SocketAddr), choice: Option<&str>) -> Value {
        let mut args = self.args(pc);
        args["game"] = json!({ "key": "steam:1", "title": "Game" });
        args["roots"] = json!({ "<winAppData>": self.appdata() });
        args["baseline"] = json!(self.dir.join(format!("{}/saves/steam_1.json", pc.1.to_hex())));
        args["archive"] = json!(self.dir.join("archive"));
        args["name"] = json!(self.name);
        if let Some(choice) = choice {
            args["choice"] = json!(choice);
        }
        let out: Value = serde_json::from_str(&call("sync_saves", &args.to_string())).unwrap();
        assert!(out.get("error").is_none() && out.get("unreachable").is_none(), "{} with {}: {out}", self.name, pc.0.name());
        out
    }
}

fn write_at(path: &Path, contents: &str, secs: u64) {
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, contents).unwrap();
    fs::File::options().write(true).open(path).unwrap().set_modified(UNIX_EPOCH + Duration::from_secs(secs)).unwrap();
}

fn read(path: &Path) -> String {
    fs::read_to_string(path).unwrap_or_default()
}

#[test]
fn three_handhelds_and_three_computers_in_a_partial_mesh() {
    let (h1, h2, h3) = (Handheld::new("H1"), Handheld::new("H2"), Handheld::new("H3"));
    let c1 = start("PC-1", &["steam:1", "steam:2"], vec![h1.id]);
    let c2 = start("PC-2", &["steam:1"], vec![h1.id, h2.id]);
    let c3 = start("PC-3", &["gog:5"], vec![h2.id, h3.id]);

    // A handheld a computer is not paired with is refused.
    let refused: Value = serde_json::from_str(&call(
        "sync_library",
        &{
            let mut a = h3.args(&c1);
            a["state"] = json!(h3.dir.join("refused.json"));
            a["scan"] = json!([]);
            a
        }
        .to_string(),
    ))
    .unwrap();
    assert!(refused.get("error").is_some() || refused.get("unreachable").is_some(), "{refused}");

    // The library relays across the mesh in one round.
    for (h, c) in [(&h1, &c1), (&h1, &c2), (&h2, &c2), (&h2, &c3), (&h3, &c3)] {
        h.library(c);
    }
    let lib = Library::load(&h3.dir.join("library.json")).unwrap();
    let game = &lib.games["steam:1"];
    let on: Vec<&str> = game.installs.values().map(|d| d.device_name.as_str()).collect();
    assert_eq!(game.installs.len(), 2, "one entry, installed on two computers: {on:?}");
    assert!(on.contains(&"PC-1") && on.contains(&"PC-2"));
    assert!(lib.games.contains_key("steam:2") && lib.games.contains_key("gog:5"));
    // Each computer's version of it, remembered three hops away (Droidtop/tracker#469 part 2).
    let seen: Vec<&str> = game.versions.iter().map(|v| v.version.as_str()).collect();
    assert!(seen.contains(&"PC-1 build") && seen.contains(&"PC-2 build"), "{seen:?}");
    // And back the other way: PC-3's game reaches H1 through H2 and PC-2.
    h2.library(&c2);
    h1.library(&c2);
    assert!(Library::load(&h1.dir.join("library.json")).unwrap().games.contains_key("gog:5"));

    // Organization travels too, newest change per field (Droidtop/tracker#469):
    // H1 rates a game and puts it in a collection; H3, three hops away,
    // is told to write both.
    let unset = json!({ "steam:1": { "rating": 0.0, "collections": [], "title": "" } });
    h1.library_with(&c2, json!({ "steam:1": { "rating": 0.8, "collections": ["Done", "Platformers"], "title": "" } }));
    // H2 does not have the game, so it reports no marks on it and only relays.
    h2.library(&c2);
    h2.library(&c3);
    let told = h3.library_with(&c3, unset.clone());
    assert_eq!(told["marks"], json!({ "steam:1": { "rating": 0.8, "collections": ["Done", "Platformers"] } }), "{told}");

    // The real agent, PC-A, shares H1 and H3 with the rest of the mesh. Its game
    // folder holds the test game as a download names it: "DtSyncTest v1.1".
    let games = temp("pc-a-games");
    fs::create_dir_all(games.join("DtSyncTest v1.1")).unwrap();
    fs::write(games.join("DtSyncTest v1.1/DtSyncTest.exe"), b"MZ").unwrap();
    let (pca, dirs) = start_agent("PC-A", &games, vec![h1.id, h3.id]);
    // A copy of the agent opened before anything happens, like the headless
    // `run` while a window or a command line is used (Droidtop/tracker#528, #531).
    let stale = Agent::open_in(dirs.clone()).unwrap();
    let game = "title:dt sync test";
    // H1 has the same game from a folder "DtSyncTest v1.0", titled the way droidtop
    // titles it, and has favourited it.
    let on_h1 = json!([{ "key": game, "title": "Dt Sync Test", "platform": "pc",
        "install": { "installed": true, "version": "1.0", "path": "/sdcard/Download/dt-games/DtSyncTest v1.0", "launcher": "droidtop" } }]);
    let favourite = json!({ game: { "favourite": true, "hidden": false, "collections": [] } });
    h1.library_scanning(&pca, on_h1.clone(), favourite.clone());
    // Droidtop/tracker#529: it is one game on both, so H1 knows the computer's v1.1 beside its own v1.0.
    let h1_lib = Library::load(&h1.dir.join("library.json")).unwrap();
    let versions: Vec<(&str, &str)> = h1_lib.games[game].versions.iter().map(|v| (v.device_name.as_str(), v.version.as_str())).collect();
    assert!(versions.contains(&("PC-A", "1.1")) && versions.contains(&("H1", "1.0")), "{versions:?}");
    assert_eq!(h1_lib.games[game].installs.len(), 2);
    // Droidtop/tracker#531: the agent restarts and rescans twice, and the stale copy scans and records a
    // visit as well; the favourite is still there.
    let restarted = Agent::open_in(dirs.clone()).unwrap();
    restarted.rescan();
    stale.rescan();
    stale.store.seen(&h1.id, None);
    restarted.rescan();
    let kept = Library::load(&dirs.data.join("library.json")).unwrap();
    assert_eq!(kept.games[game].marks["favourite"].value, json!(true), "marks: {:?}", kept.games[game].marks);
    assert_eq!(kept.games[game].installs.len(), 2);
    // H3, three hops from H1, is told to write it, through PC-A.
    let told = h3.library_with(&pca, json!({ game: { "favourite": false, "hidden": false, "collections": [] } }));
    assert_eq!(told["marks"][game]["favourite"], json!(true), "{told}");
    // PC to handheld: marks set on the computer (the window or `droidtop-agent mark`) reach H1.
    assert!(restarted.store.set_mark(game, "hidden", json!(true)));
    assert!(restarted.store.set_mark(game, "collections", json!(["Done"])));
    let from_pc = h1.library_scanning(&pca, on_h1.clone(), favourite.clone());
    assert_eq!(from_pc["marks"], json!({ game: { "hidden": true, "collections": ["Done"] } }), "{from_pc}");
    // The computer loses its library (a restore of an old state): H1 sends everything again and the favourite returns.
    fs::remove_file(dirs.data.join("library.json")).unwrap();
    let marks_back = json!({ game: { "favourite": true, "hidden": true, "collections": ["Done"] } });
    h1.library_scanning(&pca, on_h1, marks_back);
    let healed = Library::load(&dirs.data.join("library.json")).unwrap();
    assert_eq!(healed.games[game].marks["favourite"].value, json!(true), "{:?}", healed.games.get(game).map(|g| &g.marks));
    assert_eq!(healed.games[game].marks["hidden"].value, json!(true));

    // Saves: PC-1's reach H1, then PC-2, then H2.
    write_at(&c1.0.save(), "pc-1 v1", 1_000);
    assert_eq!(h1.saves(&c1, None)["from"], "there");
    assert_eq!(h1.saves(&c2, None)["from"], "here");
    assert_eq!(h2.saves(&c2, None)["from"], "there");
    assert_eq!(read(&h2.save()), "pc-1 v1");

    // H1 and H2 both play: H1's reaches PC-2 first. H2's meets it there, and
    // nobody wins: the sync says whose saves are whose, and moves nothing.
    write_at(&h1.save(), "h1 v2", 2_000);
    write_at(&h2.save(), "h2 v2", 3_000);
    assert_eq!(h1.saves(&c2, None)["from"], "here");
    let asked = h2.saves(&c2, None);
    assert_eq!(asked["outcome"], "conflict", "{asked}");
    assert_eq!((asked["here"]["device"].as_str(), asked["there"]["device"].as_str()), (Some("H2"), Some("PC-2")), "{asked}");
    assert_eq!(read(&c2.0.save()), "h1 v2");
    assert_eq!(read(&h2.save()), "h2 v2");
    // The person keeps H2's: PC-2 keeps H1's set as its own copy first.
    let newer = h2.saves(&c2, Some("here"));
    assert_eq!((newer["from"].as_str(), newer["kept"].as_bool()), (Some("here"), Some(true)), "{newer}");
    assert_eq!(read(&c2.0.save()), "h2 v2");
    assert_eq!(read(&copy_dir(&c2.0.dir.join("archive"), "PC-2").join("winAppData/Game/slot.sav")), "h1 v2");
    // H1 then takes the newest from PC-2.
    assert_eq!(h1.saves(&c2, None)["from"], "there");
    assert_eq!(read(&h1.save()), "h2 v2");

    // H3 gets H2's saves through the computer they share.
    assert_eq!(h2.saves(&c3, None)["from"], "here");
    assert_eq!(h3.saves(&c3, None)["from"], "there");
    assert_eq!(read(&h3.save()), "h2 v2");

    // Both changed: it asks, and the person keeps PC-1's older copy; H1 keeps its own.
    write_at(&c1.0.save(), "pc-1 v3", 4_000);
    write_at(&h1.save(), "h1 v3", 5_000);
    assert_eq!(h1.saves(&c1, None)["outcome"], "conflict");
    let picked = h1.saves(&c1, Some("there"));
    assert_eq!((picked["from"].as_str(), picked["kept"].as_bool()), (Some("there"), Some(true)), "{picked}");
    assert_eq!(read(&h1.save()), "pc-1 v3");
    assert_eq!(read(&copy_dir(&h1.dir.join("archive"), "H1").join("winAppData/Game/slot.sav")), "h1 v3");
}

#[test]
fn a_computer_that_moved_to_windowcasts_identity_tells_its_handhelds() {
    let h = Handheld::new("Mover");
    let (old, new) = (DeviceKey::generate(), DeviceKey::generate());
    let (old_id, new_id) = (old.peer_id(), new.peer_id());
    let computer: &'static Computer = Box::leak(Box::new(Computer {
        name: "PC".into(),
        dir: temp("moved"),
        library: Mutex::new(Library::default()),
        publish_after_read: Mutex::new(None),
    }));
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let at = listener.local_addr().unwrap();
    let paired = h.id;
    thread::spawn(move || {
        for stream in listener.incoming().flatten() {
            let (Ok((mut ch, which)), moved) = (Channel::accept_any(stream, &[&new, &old], |p| *p == paired), Moved::new(&old, &new))
            else {
                continue;
            };
            let _ = if which == 0 { serve(&mut ch, computer) } else { serve_moved(&mut ch, computer, Some(moved)) };
        }
    });
    // Reached at the identity it had: it names the new one, signed by both.
    let out = h.library(&(computer, old_id, at));
    assert_eq!(out["moved_to"], json!(new_id.to_hex()), "{out}");
    // Re-pinned, the handheld reaches it at the new one, and hears nothing more.
    let again = h.library(&(computer, new_id, at));
    assert!(again.get("moved_to").is_none(), "{again}");
}

/// Droidtop/tracker#529 and #469 part 3, the console check in one test: the
/// real agent, reached only through its WireGuard endpoint, has a newer
/// version of a game the handheld has. The library sync tells the handheld
/// about it (what the Versions tab shows), and droidtop's `game_pull` (what
/// its Downloads job calls) copies that version beside the one it had.
#[test]
fn a_newer_version_on_the_computer_is_seen_and_copied_through_the_tunnel() {
    let h = Handheld::new("Copier");
    let games = temp("tunnel-pc-games");
    let newer = games.join("DtSyncTest v1.1");
    fs::create_dir_all(newer.join("game")).unwrap();
    fs::write(newer.join("DtSyncTest.exe"), b"MZ v1.1").unwrap();
    fs::write(newer.join("game/data.bin"), vec![7u8; 300_000]).unwrap();
    let ((agent, id, _lan), _dirs) = start_agent("PC-T", &games, vec![h.id]);

    // Its WireGuard endpoint, as `droidtop-agent run` serves it.
    let udp = UdpSocket::bind("127.0.0.1:0").unwrap();
    let port = udp.local_addr().unwrap().port();
    let stop: &'static AtomicBool = Box::leak(Box::new(AtomicBool::new(false)));
    let seed = agent.store.key.seed();
    let paired = h.id;
    let server = thread::spawn(move || {
        let key = DeviceKey::from_seed(&seed).unwrap();
        tunnel::serve(
            &key,
            udp,
            move || vec![paired],
            move |peer, mut stream| {
                thread::spawn(move || {
                    stream.set_read_timeout(Some(Duration::from_secs(20)));
                    let key = DeviceKey::from_seed(&seed).unwrap();
                    if let Ok(mut ch) = Channel::accept(stream, &key, |p| *p == peer) {
                        let _ = serve(&mut ch, agent);
                    }
                });
            },
            stop,
            &mut tunnel::NoHooks,
        )
        .unwrap();
    });
    // No LAN address answers: only the endpoint the computer gave last time.
    let through_tunnel = |extra: Value| {
        let mut args =
            json!({ "seed": h.seed, "peer": id.to_hex(), "name": h.name, "addresses": ["127.0.0.1:1", format!("wg:127.0.0.1:{port}")] });
        for (k, v) in extra.as_object().unwrap() {
            args[k] = v.clone();
        }
        args.to_string()
    };

    // The handheld has v1.0 from its own folder, titled the way droidtop titles it.
    let shelf = h.dir.join("dt-games");
    let older = shelf.join("DtSyncTest v1.0");
    fs::create_dir_all(&older).unwrap();
    fs::write(older.join("DtSyncTest.exe"), b"MZ v1.0").unwrap();
    let key = "title:dt sync test";
    let synced: Value = serde_json::from_str(&call(
        "sync_library",
        &through_tunnel(json!({
            "state": h.dir.join("library.json"),
            "scan": [{ "key": key, "title": "Dt Sync Test", "platform": "pc",
                "install": { "installed": true, "version": "1.0", "path": older, "launcher": "droidtop" } }],
        })),
    ))
    .unwrap();
    assert!(synced.get("error").is_none() && synced.get("unreachable").is_none(), "{synced}");
    assert_eq!(synced["path"], "wireguard", "{synced}");
    let lib = Library::load(&h.dir.join("library.json")).unwrap();
    let versions: Vec<(&str, &str)> = lib.games[key].versions.iter().map(|v| (v.device_name.as_str(), v.version.as_str())).collect();
    assert!(versions.contains(&("PC-T", "1.1")) && versions.contains(&("Copier", "1.0")), "{versions:?}");

    // Get v1.1 from the computer: a new folder beside v1.0, which stays.
    let progress = h.dir.join("progress.json");
    let copied: Value = serde_json::from_str(&call(
        "game_pull",
        &through_tunnel(json!({
            "game": { "key": key, "title": "Dt Sync Test" },
            "parent": shelf, "folder_name": "Dt Sync Test v1.1", "progress": progress,
        })),
    ))
    .unwrap();
    stop.store(true, Ordering::Relaxed);
    server.join().unwrap();
    assert_eq!(copied["path"], "wireguard", "{copied}");
    assert_eq!(copied["files"], 2, "{copied}");
    assert_eq!(fs::read(shelf.join("Dt Sync Test v1.1/DtSyncTest.exe")).unwrap(), b"MZ v1.1");
    assert_eq!(fs::read(shelf.join("Dt Sync Test v1.1/game/data.bin")).unwrap().len(), 300_000);
    assert_eq!(fs::read(older.join("DtSyncTest.exe")).unwrap(), b"MZ v1.0");
    let p: Value = serde_json::from_str(&fs::read_to_string(progress).unwrap()).unwrap();
    assert_eq!(p["done"], p["total"]);
}

/// Docs/DESIGN.md section 8, protocol 2, from the handheld: a context is read
/// as the computer's current copy, merged per field, and put back whole. A
/// computer whose copy moved between the read and the put refuses the put as
/// stale; the handheld reads, merges and puts again.
#[test]
fn a_context_syncs_by_copies_and_a_copy_that_moved_is_merged_again() {
    let h = Handheld::new("H1");
    let pc = start("PC-copy", &[], vec![h.id]);
    let cur = |pc: &(&'static Computer, PeerId, SocketAddr)| {
        droidtop_agent_core::copy::CopyStore::open(&pc.0.dir.join("adapters"), "f95checker").unwrap().current().unwrap()
    };
    let rec = |k: &str, installed: &str, version: &str| -> Value { json!({ k: { "installed": installed, "version": version } }) };

    // The computer's plugin publishes what F95Checker holds; the handheld brings it down.
    droidtop_agent_core::copy::CopyStore::open(&pc.0.dir.join("adapters"), "f95checker")
        .unwrap()
        .publish(serde_json::from_value(rec("100", "0.1", "v1")).unwrap(), Vec::new())
        .unwrap();
    let first = h.context_copy(&pc, json!({}), json!({}));
    assert_eq!(first["device"]["100"]["version"], "v1", "{first}");
    assert_eq!(first["generation"], 1, "{first}");
    assert_eq!(first["sent"], 0, "{first}");

    // The handheld changes a field: one put, one new generation on the computer.
    let played = h.context_copy(&pc, rec("100", "0.2", "v1"), first["baseline"].clone());
    assert_eq!((played["sent"].as_u64(), played["generation"].as_u64()), (Some(1), Some(2)), "{played}");
    assert_eq!(cur(&pc).records["100"]["installed"], "0.2");

    // The computer's plugin publishes between the read and the put: the put is
    // refused as stale, and the next try merges both changes.
    *pc.0.publish_after_read.lock().unwrap() = Some(serde_json::from_value(rec("100", "0.2", "v2")).unwrap());
    let both = h.context_copy(&pc, rec("100", "0.3", "v1"), played["baseline"].clone());
    assert_eq!(both["sent"], 1, "{both}");
    let now = cur(&pc);
    assert_eq!((now.records["100"]["installed"].clone(), now.records["100"]["version"].clone()), (json!("0.3"), json!("v2")), "{both}");
    assert_eq!(both["device"]["100"]["version"], "v2", "{both}");
    assert_eq!(both["generation"].as_u64(), Some(now.generation));
}
