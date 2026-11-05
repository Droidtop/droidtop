//! The operations droidtop calls, JSON in and JSON out, so the Kotlin side
//! needs no types of its own beyond reading the replies. A failure comes back
//! as `{"error": "..."}` in words the screens can show; a computer that
//! could not be reached as `{"unreachable": "..."}`; a computer that answered
//! as itself and no longer knows this device as `{"error": "...", "code":
//! "not_paired"}` (Droidtop/tracker#531).

use droidtop_agent_core::sync::LockExt;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use droidtop_agent_core::accept::Cancel;
use droidtop_agent_core::cert;
use droidtop_agent_core::channel::Channel;
use droidtop_agent_core::client::{self, ConnectError, RendezvousSettings, Session, Target};
use droidtop_agent_core::confirm;
use droidtop_agent_core::context::{apply, baseline_when_deferred, merge, AdapterOffer, ContextDecl, Records};
use droidtop_agent_core::copy;
use droidtop_agent_core::discovery::{self, Announce};
use droidtop_agent_core::keys::{DeviceKey, PeerId};
use droidtop_agent_core::library::{Change, Cursor, Library, Marks, ScannedGame};
use droidtop_agent_core::mailbox::{self, Envelope};
use droidtop_agent_core::pairing::{self, Kind, PairInvite, Paired, PairingEnded};
use droidtop_agent_core::proto::{check_announcements, Announcements, GameRef, Request, Response, StreamAsk};
use droidtop_agent_core::saves::{prefix_roots, Roots};
use droidtop_agent_core::savesync::{self, SaveSyncRequest, Side};
use droidtop_agent_core::{hex, Error, PAIR_PORT, PORT};
use net_remote::{Error as RemoteError, Remote, RemotePath, WebDav, WebDavConfig};
use serde::Deserialize;
use serde_json::{json, Value};

pub fn error(message: &str) -> String {
    json!({ "error": message }).to_string()
}

pub fn call(op: &str, args: &str) -> String {
    let args: Value = serde_json::from_str(args).unwrap_or(Value::Null);
    let result = match op {
        "identity_new" => Ok(identity_new()),
        "identity_id" => identity_id(&args),
        "pair_start" => pair_start(&args),
        "pair_wait" => pair_wait(&args),
        "pair_connect" => pair_connect(&args),
        "pair_cancel" => {
            if let Some(cancel) = CANCEL.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
                cancel.cancel();
            }
            Ok(json!({}))
        }
        "computers_find" => computers_find(&args),
        "compare_start" => compare_start(&args),
        "compare_finish" => compare_finish(&args),
        "compare_cancel" => {
            COMPARE.lock().unwrap_or_else(|e| e.into_inner()).take();
            Ok(json!({}))
        }
        "find" => find(&args),
        "sync_saves" => sync_saves(&args),
        "restore_saves" => restore_saves(&args),
        "kept_saves" => kept_saves(&args),
        "sync_library" => sync_library(&args),
        "sync_context" => sync_context(&args),
        "sync_context_copy" => sync_context_copy(&args),
        "game_pull" => game_pull(&args),
        "game_push" => game_push(&args),
        "launch_game" => launch_game(&args),
        "announce" => announce(&args),
        "serve_start" => serve_start(&args),
        "serve_stop" => serve_stop(),
        "share_library" => share_library(&args),
        "share_post_saves" => share_post_saves(&args),
        "remote_test" => remote_test(&args),
        "remote_list" => remote_list(&args),
        "remote_put" => remote_put(&args),
        "remote_get" => remote_get(&args),
        // KDE Connect (Droidtop/tracker#645, docs/SPEC.md 7q "KDE Connect"): kdeconnect_<op> is
        // lib-kdeconnect's operation <op>, passed through as it is.
        op if op.starts_with("kdeconnect_") => return kdeconnect_service::ops::call(&op["kdeconnect_".len()..], &args).to_string(),
        other => Err(Failure::Error(format!("unknown operation {other}"))),
    };
    match result {
        Ok(v) => v.to_string(),
        Err(Failure::Error(m)) => error(&m),
        Err(Failure::Unreachable(m)) => json!({ "unreachable": m }).to_string(),
        Err(Failure::NotPaired(m)) => json!({ "error": m, "code": droidtop_agent_core::proto::NOT_PAIRED }).to_string(),
        Err(Failure::Coded(code, m)) => json!({ "error": m, "code": code }).to_string(),
    }
}

enum Failure {
    Error(String),
    Unreachable(String),
    /// The computer answered as itself and said it no longer knows this device.
    NotPaired(String),
    /// A failure droidtop tells apart by its code (a share's sign-in refused, an unencrypted address).
    Coded(&'static str, String),
}

impl From<Error> for Failure {
    fn from(e: Error) -> Self {
        Failure::Error(e.to_string())
    }
}

impl From<serde_json::Error> for Failure {
    fn from(e: serde_json::Error) -> Self {
        Failure::Error(format!("droidtop sent arguments the agent library cannot read ({e})"))
    }
}

type Outcome = Result<Value, Failure>;

fn key_of(args: &Value) -> Result<DeviceKey, Failure> {
    let seed = args["seed"].as_str().and_then(hex::decode).ok_or_else(|| Failure::Error("no device key was given".into()))?;
    Ok(DeviceKey::from_seed(&seed)?)
}

fn peer_of(text: &str) -> Result<PeerId, Failure> {
    PeerId::from_hex(text).map_err(|_| Failure::Error("that computer's id is not valid".into()))
}

/// A new device key: its seed (for droidtop to keep sealed), its id and its
/// certificate's fingerprint (the pin a paired device keeps).
fn identity_new() -> Value {
    let key = DeviceKey::generate();
    json!({ "seed": hex::encode(&key.seed()), "id": key.peer_id().to_hex(), "fingerprint": cert::fingerprint_hex(&key.peer_id()) })
}

fn identity_id(args: &Value) -> Outcome {
    let id = key_of(args)?.peer_id();
    Ok(json!({ "id": id.to_hex(), "fingerprint": cert::fingerprint_hex(&id) }))
}

/// What this device is, for the device it pairs with: droidtop is a
/// handheld unless it says otherwise.
fn kind_of(args: &Value) -> Result<Kind, Failure> {
    match &args["kind"] {
        Value::Null => Ok(Kind::Handheld),
        v => serde_json::from_value(v.clone()).map_err(|_| Failure::Error("kind is computer or handheld".into())),
    }
}

/// A pairing's result as droidtop keeps it: the device's id, name, kind
/// and certificate fingerprint, and where it is reached.
fn paired_json(paired: &Paired, address: String) -> Value {
    json!({ "peer": paired.peer.to_hex(), "name": paired.name, "kind": paired.kind, "fingerprint": paired.fingerprint, "address": address })
}

// Pairing --------------------------------------------------------------------

struct PairSession {
    listener: TcpListener,
    code: String,
    key: DeviceKey,
    name: String,
    kind: Kind,
    stop: Arc<AtomicBool>,
}

static SESSION: Mutex<Option<PairSession>> = Mutex::new(None);
/// The pairing screen's cancel, shared with `pair_cancel` while `pair_wait` is blocked on the listener.
static CANCEL: Mutex<Option<Arc<Cancel>>> = Mutex::new(None);

/// Opens the pairing listener for as long as the pairing screen is open,
/// and answers the computer's pairing query on the LAN.
fn pair_start(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let kind = kind_of(args)?;
    let name = args["name"].as_str().unwrap_or("droidtop").to_string();
    if let Some(old) = SESSION.locked().take() {
        old.stop.store(true, Ordering::Relaxed);
    }
    // A pairing screen that is still waiting is ended by opening a new one.
    if let Some(old) = CANCEL.lock().unwrap_or_else(|e| e.into_inner()).replace(Arc::new(Cancel::new())) {
        old.cancel();
    }
    let listener = TcpListener::bind(("0.0.0.0", 0)).map_err(|e| Failure::Error(format!("could not open a pairing port ({e})")))?;
    let port = listener.local_addr().map_err(|e| Failure::Error(e.to_string()))?.port();
    let code = pairing::new_code();
    let stop = Arc::new(AtomicBool::new(false));
    let announce = Announce { id: key.peer_id().to_hex(), name: name.clone(), port, sig: String::new() };
    // The UDP answer is a convenience: without it the computer still pairs
    // with the address in the QR code's text.
    if let Ok(sock) = UdpSocket::bind(("0.0.0.0", PORT)) {
        let stop = stop.clone();
        thread::spawn(move || {
            let _ = discovery::answer_pairing(&sock, &announce, &stop);
        });
    }
    let at = args["address"].as_str().filter(|a| !a.is_empty()).map(|a| format!("{a}:{port}"));
    let invite = PairInvite { code: code.clone(), peer: Some(key.peer_id()), name: Some(name.clone()), at };
    let uri = invite.to_uri();
    *SESSION.locked() = Some(PairSession { listener, code: code.clone(), key, name, kind, stop });
    Ok(json!({ "code": code, "uri": uri, "port": port }))
}

/// Waits for a device to pair (a computer, or another handheld that typed
/// the code), up to `timeout_ms`, or until cancelled.
fn pair_wait(args: &Value) -> Outcome {
    let timeout = Duration::from_millis(args["timeout_ms"].as_u64().unwrap_or(600_000));
    let session = SESSION.locked().take().ok_or_else(|| Failure::Error("pairing was not started".into()))?;
    let cancel =
        CANCEL.lock().unwrap_or_else(|e| e.into_inner()).clone().ok_or_else(|| Failure::Error("pairing was not started".into()))?;
    let result = pairing::accept_pairing(
        &session.listener,
        &session.key,
        &session.name,
        session.kind,
        &session.code,
        &cancel,
        Instant::now() + timeout,
        |_, _| {},
    );
    session.stop.store(true, Ordering::Relaxed);
    match result {
        Ok((paired, from)) => Ok(paired_json(&paired, format!("{}:{PORT}", from.ip()))),
        Err(PairingEnded::Cancelled) => Err(Failure::Error("pairing was cancelled".into())),
        Err(PairingEnded::TimedOut) => Err(Failure::Error("no computer paired in time".into())),
        Err(PairingEnded::TooManyWrong(e)) => {
            Err(Failure::Error(format!("pairing stopped after {} wrong codes ({e})", pairing::MAX_ATTEMPTS)))
        }
        Err(PairingEnded::Io(e)) => Err(Failure::Error(e.to_string())),
    }
}

/// Pairing the other way round: the other device shows its address and a
/// code (`droidtop-agent pair` with nothing after it, or another handheld's
/// pairing screen), and this device connects. For a handheld the computer
/// cannot reach, such as one behind an emulator's or a guest network's NAT,
/// and for pairing two handhelds. The other device is then reached at the
/// same address on the agent's port.
fn pair_connect(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let kind = kind_of(args)?;
    let name = args["name"].as_str().unwrap_or("droidtop").to_string();
    let invite = PairInvite::parse(args["code"].as_str().unwrap_or_default())
        .ok_or_else(|| Failure::Error("the code is the 6 digits the computer shows".into()))?;
    let typed = args["address"].as_str().map(str::trim).unwrap_or_default();
    let at = invite.at.as_deref().unwrap_or(typed);
    let address: SocketAddr = at
        .parse()
        .or_else(|_| format!("{at}:{PAIR_PORT}").parse())
        .map_err(|_| Failure::Error(format!("{at} is not an address such as 192.168.1.20 or 192.168.1.20:{PAIR_PORT}")))?;
    let mut stream = TcpStream::connect_timeout(&address, Duration::from_secs(10))
        .map_err(|e| Failure::Unreachable(format!("the computer did not answer at {address} ({e})")))?;
    stream
        .set_read_timeout(Some(Duration::from_secs(60)))
        .map_err(|e| Failure::Error(format!("could not set up the connection to {address} ({e})")))?;
    let paired = pairing::pair_client(&mut stream, &key, &name, kind, &invite.code)?;
    if invite.peer.is_some_and(|p| p != paired.peer) {
        return Err(Failure::Error("the device that answered is not the one in the invitation; nothing was paired".into()));
    }
    Ok(paired_json(&paired, SocketAddr::new(address.ip(), PORT).to_string()))
}

// Pairing by comparing a code (droidtop SPEC 7o, "Pairing with Pythia") ----

/// The comparison under way: its stream, its state and the computer's address.
static COMPARE: Mutex<Option<(TcpStream, confirm::Comparing, SocketAddr)>> = Mutex::new(None);

/// Computers on this network offering to pair (they answer `DTCQ1`).
fn computers_find(args: &Value) -> Outcome {
    let timeout = Duration::from_millis(args["timeout_ms"].as_u64().unwrap_or(1500));
    let found = discovery::find_computers(timeout).map_err(|e| Failure::Error(e.to_string()))?;
    Ok(
        json!({ "found": found.iter().map(|(o, at)| json!({ "id": o.id, "name": o.name, "program": o.program, "address": at.to_string() })).collect::<Vec<_>>() }),
    )
}

/// Picks a listed computer and starts the comparison: returns the code to show.
fn compare_start(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let want = peer_of(args["id"].as_str().unwrap_or_default())?;
    let address: SocketAddr =
        args["address"].as_str().and_then(|a| a.parse().ok()).ok_or_else(|| Failure::Error("no address for that computer".into()))?;
    let mut stream = TcpStream::connect_timeout(&address, Duration::from_secs(10))
        .map_err(|e| Failure::Unreachable(format!("the computer did not answer at {address} ({e})")))?;
    stream
        .set_read_timeout(Some(Duration::from_secs(120)))
        .map_err(|e| Failure::Error(format!("could not set up the connection to {address} ({e})")))?;
    let comparing = confirm::client_start(&mut stream, &key)?;
    if comparing.other != want {
        return Err(Failure::Error("the device that answered is not the computer picked; nothing was paired".into()));
    }
    let code = comparing.code.clone();
    *COMPARE.lock().unwrap_or_else(|e| e.into_inner()) = Some((stream, comparing, address));
    Ok(json!({ "code": code }))
}

/// The person's answer (`same`): pins the computer when both said the codes match.
fn compare_finish(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let kind = kind_of(args)?;
    let name = args["name"].as_str().unwrap_or("droidtop").to_string();
    let same = args["same"].as_bool().unwrap_or(false);
    let (mut stream, comparing, address) =
        COMPARE.lock().unwrap_or_else(|e| e.into_inner()).take().ok_or_else(|| Failure::Error("no comparison was started".into()))?;
    let paired = comparing.finish(&mut stream, &key, &name, kind, same)?;
    Ok(paired_json(&paired, SocketAddr::new(address.ip(), PORT).to_string()))
}

// Reaching a paired computer -------------------------------------------------

fn find(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let peers: Vec<PeerId> = args["peers"]
        .as_array()
        .map(|a| a.iter().filter_map(|p| p.as_str()).filter_map(|p| PeerId::from_hex(p).ok()).collect())
        .unwrap_or_default();
    let timeout = Duration::from_millis(args["timeout_ms"].as_u64().unwrap_or(1500));
    let found = discovery::find_agents(&key.peer_id(), &peers, timeout).map_err(|e| Failure::Error(e.to_string()))?;
    Ok(
        json!({ "found": found.iter().map(|f| json!({ "id": f.peer.to_hex(), "name": f.name, "address": f.addr.to_string() })).collect::<Vec<_>>() }),
    )
}

/// A session with the computer (`core::client`), from droidtop's arguments:
/// `peer`, `addresses`, `name`, and `disco` with `rendezvous` when it has them.
fn connect(key: &DeviceKey, args: &Value) -> Result<Session, Failure> {
    let peer = peer_of(args["peer"].as_str().unwrap_or_default())?;
    let addresses: Vec<String> =
        args["addresses"].as_array().map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect()).unwrap_or_default();
    let settings: Option<RendezvousSettings> = serde_json::from_value(args["rendezvous"].clone()).ok();
    let target = Target {
        peer,
        name: args["name"].as_str().unwrap_or("droidtop"),
        addresses: &addresses,
        disco: args["disco"].as_str(),
        rendezvous: settings.as_ref(),
    };
    client::connect(key, &target).map_err(|e| match e {
        ConnectError::Unreachable(m) => Failure::Unreachable(m),
        ConnectError::NotPaired => Failure::NotPaired(
            "it no longer knows this device (it was unpaired there): pair it again in Settings, Computers, Pair a computer".into(),
        ),
        ConnectError::Failed(e) => Failure::Error(e.to_string()),
    })
}

fn bye(s: Session) {
    s.bye();
}

/// Adds where the computer answered, its name and its endpoints to a reply.
fn located(mut v: Value, s: &Session) -> Value {
    if !s.address.is_empty() {
        v["address"] = json!(s.address);
    }
    v["computer"] = json!(s.name);
    v["endpoints"] = json!(s.endpoints);
    v["path"] = json!(s.path.name());
    if let Some(disco) = &s.disco {
        v["disco"] = json!(disco);
    }
    if let Some(moved) = &s.moved_to {
        v["moved_to"] = json!(moved.to_hex());
    }
    v
}

// Saves ----------------------------------------------------------------------

#[derive(Deserialize)]
struct SavesArgs {
    game: GameRef,
    /// The game's Wine prefix (`drive_c`) and Windows user, when it runs in one.
    #[serde(default)]
    prefix: Option<PathBuf>,
    #[serde(default)]
    user: Option<String>,
    /// The game's folder.
    #[serde(default)]
    base: Option<PathBuf>,
    /// Folders for further tokens, as the caller resolves them.
    #[serde(default)]
    roots: Roots,
    baseline: PathBuf,
    archive: PathBuf,
    /// This device's name, for the copy it keeps of its own saves.
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    choice: Option<Side>,
}

impl SavesArgs {
    /// This device's folder for each token, for this game.
    fn roots(&self) -> Roots {
        let mut roots = match (&self.prefix, &self.user) {
            (Some(prefix), Some(user)) => prefix_roots(prefix, user),
            _ => Roots::new(),
        };
        if let Some(base) = &self.base {
            roots.insert("<base>".into(), base.clone());
        }
        roots.extend(self.roots.clone());
        roots
    }
}

fn sync_saves(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let a: SavesArgs = serde_json::from_value(args.clone())?;
    let roots = a.roots();
    let mut s = connect(&key, args)?;
    let name = a.name.clone().unwrap_or_else(|| "droidtop".into());
    let request = SaveSyncRequest {
        game: a.game.clone(),
        roots: &roots,
        baseline_path: &a.baseline,
        archive_dir: &a.archive,
        name: &name,
        computer: &s.name,
        choice: a.choice,
    };
    let outcome = savesync::sync(&mut s.ch, &request);
    let v = located(serde_json::to_value(outcome?)?, &s);
    bye(s);
    Ok(v)
}

/// The copies of a game's saves this device keeps (Droidtop/tracker#822), for the game page's rows: each
/// device's newest copy, kept when its saves were replaced. Arguments: `archive` (the game's archive folder,
/// as `sync_saves` and `restore_saves` take it). The reply: `[{device, files, newest_ms}]`, newest first;
/// `device` is what `restore_saves` takes to bring that copy back, `files` how many save files it holds, and
/// `newest_ms` its newest file's time. The layout is lib-sync's: the folder is `savesync::copy_dir`'s, and
/// the files `savesync::copy_files` (which leaves out a copy's bookkeeping). A game with none answers `[]`.
fn kept_saves(args: &Value) -> Outcome {
    let archive = args["archive"].as_str().map(PathBuf::from).ok_or_else(|| Failure::Error("no archive folder was given".into()))?;
    let Some(copies) = savesync::copy_dir(&archive, "device").parent().map(PathBuf::from) else {
        return Ok(json!([]));
    };
    let mut kept: Vec<(i64, Value)> = Vec::new();
    for entry in std::fs::read_dir(&copies).into_iter().flatten().flatten().filter(|e| e.path().is_dir()) {
        let files = savesync::copy_files(&entry.path());
        if files.is_empty() {
            continue;
        }
        let newest_ms = files
            .iter()
            .filter_map(|(_, p)| std::fs::metadata(p).and_then(|m| m.modified()).ok())
            .filter_map(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64)
            .max()
            .unwrap_or(0);
        let device = entry.file_name().to_string_lossy().into_owned();
        kept.push((newest_ms, json!({ "device": device, "files": files.len(), "newest_ms": newest_ms })));
    }
    kept.sort_by_key(|k| std::cmp::Reverse(k.0));
    Ok(Value::Array(kept.into_iter().map(|(_, v)| v).collect()))
}

/// Puts a kept copy of a game's saves back on this device
/// (Droidtop/tracker#822): after "Saves differ" was settled, the side that
/// was replaced kept its set as its device's copy (`savesync::copy_dir`),
/// and this brings it back. Needs no computer.
///
/// Arguments: those of `sync_saves` (`game`, `prefix`, `user`, `base`,
/// `roots`, `baseline`, `archive`, `name`) and `device`, the name of the
/// device whose kept copy to bring back. What is here now is kept first as
/// this device's copy (`name`), so a restore can be undone the same way;
/// not when the copy brought back is this device's own, which keeping would
/// overwrite. Which files are the game's saves is the save locations this
/// device kept beside the baseline at its last sync of the game
/// (`savesync::spec_path`); a game never synced here has none, and the op
/// refuses rather than restore with nothing kept.
///
/// The reply: `restored` (files written) and `kept` (files kept first). The
/// next `sync_saves` sees this device's set changed and offers it to the
/// computer as usual.
fn restore_saves(args: &Value) -> Outcome {
    let a: SavesArgs = serde_json::from_value(args.clone())?;
    let device = args["device"].as_str().filter(|d| !d.is_empty()).ok_or_else(|| Failure::Error("no device was named".into()))?;
    let name = a.name.clone().unwrap_or_else(|| "droidtop".into());
    let copy = savesync::copy_dir(&a.archive, device);
    if !copy.is_dir() {
        return Err(Failure::Error(format!("No copy from {device} is kept for this game: older copies are dropped as newer ones come.")));
    }
    let spec: droidtop_agent_core::saves::SaveSpec = std::fs::read(savesync::spec_path(&a.baseline))
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .ok_or_else(|| Failure::Error("This game's saves were never synced here, so where they are is not known yet.".into()))?;
    let roots = a.roots();
    let mut kept = 0;
    if copy != savesync::copy_dir(&a.archive, &name) {
        let here = droidtop_agent_core::saves::collect(&spec, &roots);
        kept = here.iter().filter(|(_, p)| p.is_file()).count();
        savesync::keep_copy(here.into_iter(), &a.archive, &name)?;
    }
    let restored = savesync::restore_copy(&copy, &roots)?;
    Ok(json!({ "restored": restored, "kept": kept }))
}

/// Syncs a context by copies (docs/DESIGN.md section 8; adapter protocol 2):
/// reads the computer's current copy, merges it three-way per field with the
/// device's published copy against the baseline, and puts the merged copy
/// back in one step. Arguments: `decl`, `device` (the records of the copy the
/// plugin published here), `baseline` (the records as they were after the
/// last sync with this computer), and the connection's. The reply:
/// `device` (the records to make this device's copy, after the merge),
/// `baseline`, `conflicts` (fields both sides changed whose rule is `ask`),
/// `generation` (the computer's copy after this sync), `sent` (the changes
/// the computer received), `changed` (the computer's copy differed from the
/// baseline or the device's), and `deferred` (the plugin there has not taken
/// the copy in yet, and says why in `message`; the copy is current anyway,
/// so the baseline moves).
///
/// A computer whose copy changed between the read and the put refuses the
/// put as `stale`; the copy is then read, merged and put again, three times
/// at most, and the context is left for the next sync after that.
fn sync_context_copy(args: &Value) -> Outcome {
    const TRIES: usize = 3;
    let key = key_of(args)?;
    let decl: ContextDecl = serde_json::from_value(args["decl"].clone())?;
    let device: Records = serde_json::from_value(args["device"].clone()).unwrap_or_default();
    let baseline: Records = serde_json::from_value(args["baseline"].clone()).unwrap_or_default();
    let mut s = connect(&key, args)?;
    let ch = &mut s.ch;
    let mut last = None;
    for _ in 0..TRIES {
        ch.send_json(&Request::ContextCopyGet { context: decl.id.clone() })?;
        let (generation, theirs) = match ch.recv_json::<Response>()? {
            Response::ContextCopy { generation, records, .. } => (generation, records),
            Response::Error { message, .. } => return Err(Failure::Error(message)),
            other => return Err(Failure::Error(format!("the computer answered {other:?}"))),
        };
        let m = merge(&decl, &device, &theirs, &baseline);
        if m.to_computer.is_empty() {
            let v = located(
                json!({
                    "device": m.device, "baseline": m.baseline, "conflicts": m.conflicts, "generation": generation,
                    "sent": 0, "deferred": false, "message": null,
                }),
                &s,
            );
            bye(s);
            return Ok(v);
        }
        let merged = apply(&theirs, &m.to_computer);
        ch.send_json(&Request::ContextCopyPut {
            context: decl.id.clone(),
            base_generation: generation,
            records: merged,
            files: Vec::new(),
        })?;
        match ch.recv_json::<Response>()? {
            Response::ContextCopyStored { generation, deferred } => {
                let v = located(
                    json!({
                        "device": m.device, "baseline": m.baseline, "conflicts": m.conflicts, "generation": generation,
                        "sent": m.to_computer.len(), "deferred": deferred.is_some(), "message": deferred,
                    }),
                    &s,
                );
                bye(s);
                return Ok(v);
            }
            Response::Error { message, .. } if message.contains(copy::STALE) => last = Some(message),
            Response::Error { message, .. } => return Err(Failure::Error(message)),
            other => return Err(Failure::Error(format!("the computer answered {other:?}"))),
        }
    }
    // Left for the next sync: nothing was changed on either side.
    Err(Failure::Error(format!(
        "the computer's copy of {} kept changing ({}); it is left for the next sync",
        decl.id,
        last.unwrap_or_default()
    )))
}

// Library --------------------------------------------------------------------

/// This device's side of a library exchange, read before anything travels:
/// what it has installed and the marks it keeps on those games (favourite,
/// hidden, completed), recorded in the core's library file.
struct LocalLibrary {
    lib: Library,
    state: PathBuf,
    marks: Marks,
}

impl LocalLibrary {
    fn read(key: &DeviceKey, args: &Value) -> Result<LocalLibrary, Failure> {
        let me = key.peer_id();
        let state = PathBuf::from(args["state"].as_str().ok_or_else(|| Failure::Error("no library file was given".into()))?);
        let name = args["name"].as_str().unwrap_or("droidtop").to_string();
        let scan: Vec<ScannedGame> = serde_json::from_value(args["scan"].clone()).unwrap_or_default();
        let marks: Marks = serde_json::from_value(args["marks"].clone()).unwrap_or_default();
        let mut lib = Library::load(&state).map_err(|e| Failure::Error(e.to_string()))?;
        lib.update_device(&me, &name, scan);
        lib.note_marks(&me, &marks);
        lib.save(&state).map_err(|e| Failure::Error(e.to_string()))?;
        Ok(LocalLibrary { lib, state, marks })
    }

    fn save(&self) -> Result<(), Failure> {
        self.lib.save(&self.state).map_err(|e| Failure::Error(e.to_string()))
    }

    /// The marks that arrived from elsewhere, for droidtop to write into its own library.
    fn to_write(&self) -> Value {
        json!(self.lib.marks_to_write(&self.marks))
    }
}

/// Sends [`changes`]; a library change kind the computer did not say it
/// reads ([`reads_links`], its hello's `identity`) is left out.
fn push_changes(ch: &mut Channel<impl Read + Write>, mut changes: Vec<Change>, reads_links: bool) -> Result<(), Failure> {
    if !reads_links {
        changes.retain(Change::readable_without_links);
    }
    if changes.is_empty() {
        return Ok(());
    }
    ch.send_json(&Request::LibraryPush { changes })?;
    match ch.recv_json::<Response>()? {
        Response::Ok => Ok(()),
        Response::Error { message, .. } => Err(Failure::Error(message)),
        other => Err(Failure::Error(format!("the computer answered {other:?}"))),
    }
}

/// What the computer changed after [`since`], and its own number to ask from next.
fn pull_changes(ch: &mut Channel<impl Read + Write>, since: u64) -> Result<(Vec<Change>, u64), Failure> {
    ch.send_json(&Request::LibraryPull { since })?;
    match ch.recv_json::<Response>()? {
        Response::LibraryChanges { changes, cursor } => Ok((changes, cursor)),
        Response::Error { message, .. } => Err(Failure::Error(message)),
        other => Err(Failure::Error(format!("the computer answered {other:?}"))),
    }
}

fn sync_library(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let peer_hex = args["peer"].as_str().unwrap_or_default().to_string();
    let mut local = LocalLibrary::read(&key, args)?;
    let mut s = connect(&key, args)?;
    let reads_links = s.states(droidtop_agent_core::library::LINK_FEATURE);
    let lib = &mut local.lib;
    let ch = &mut s.ch;
    let cursor: Cursor = lib.cursors.get(&peer_hex).copied().unwrap_or_default();
    let (outgoing, seq) = lib.changes_since(cursor.pushed);
    let mut pushed = outgoing.len();
    push_changes(ch, outgoing, reads_links)?;
    let (mut incoming, their) = pull_changes(ch, cursor.pulled)?;
    // The computer's log is shorter than it was when this device last pulled
    // it: it lost its library (a restore, a reinstall). What this device
    // sent before is gone from it, so send everything again and take
    // everything it has; both are applied newest-wins, so nothing is
    // doubled (Droidtop/tracker#531).
    if their < cursor.pulled {
        let (everything, _) = lib.changes_since(0);
        pushed = everything.len();
        push_changes(ch, everything, reads_links)?;
        incoming = pull_changes(ch, 0)?.0;
    }
    let pulled = incoming.iter().filter(|c| lib.apply((*c).clone())).count();
    lib.cursors.insert(peer_hex, Cursor { pulled: their, pushed: seq });
    local.save()?;
    let v = located(json!({ "pushed": pushed, "pulled": pulled, "marks": local.to_write() }), &s);
    bye(s);
    Ok(v)
}

// The person's own cloud share ----------------------------------------------
//
// droidtop reaches the share through Android's document picker, which the
// core cannot open, so it keeps two folders of its own that mirror the
// share's layout (`droidtop-agent/<recipient id>/inbox/`): `inbox`, where it
// copies what the share holds for this device before the call, and
// `outbox`, which it copies into the share after it and empties. The core
// seals, opens and applies; droidtop only moves files.

fn path_arg(args: &Value, name: &str) -> Result<PathBuf, Failure> {
    args[name].as_str().filter(|p| !p.is_empty()).map(PathBuf::from).ok_or_else(|| Failure::Error(format!("no {name} folder was given")))
}

/// The library through the share: what the computer left is applied, and
/// what it has not had yet is left for it. Letters from anyone but this
/// computer stay where they are.
fn share_library(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let peer = peer_of(args["peer"].as_str().unwrap_or_default())?;
    let inbox = path_arg(args, "inbox")?;
    let outbox = path_arg(args, "outbox")?;
    let mut local = LocalLibrary::read(&key, args)?;
    let (letters, _) = mailbox::collect(&inbox, &key, |p| *p == peer).map_err(|e| Failure::Error(e.to_string()))?;
    let mut applied = 0;
    let mut done = Vec::new();
    let mut refused = Vec::new();
    for letter in letters {
        match mailbox::unpack(&letter.payload) {
            Ok((Envelope::Library { changes }, _)) => applied += changes.into_iter().filter(|c| local.lib.apply(c.clone())).count(),
            Ok((Envelope::SavesRefused { game, reason }, _)) => {
                refused.push(json!({ "key": game.key, "title": game.title, "reason": reason }))
            }
            // A computer leaves no save sets for the handheld: its saves come at the next live sync.
            Ok((Envelope::Saves { .. }, _)) | Err(_) => {}
        }
        if let Some(name) = letter.path.file_name().and_then(|n| n.to_str()) {
            done.push(name.to_string());
        }
        let _ = std::fs::remove_file(&letter.path);
    }
    let peer_hex = peer.to_hex();
    let cursor = local.lib.cursors.get(&peer_hex).copied().unwrap_or_default();
    let (outgoing, seq) = local.lib.changes_since(cursor.pushed);
    // A letter has no hello to say what its reader reads: no links in it.
    let outgoing: Vec<Change> = outgoing.into_iter().filter(Change::readable_without_links).collect();
    let posted = outgoing.len();
    if !outgoing.is_empty() {
        let payload = mailbox::pack(&Envelope::Library { changes: outgoing }, &[])?;
        mailbox::post(&outbox, &key, &peer, &payload)?;
    }
    local.lib.cursors.entry(peer_hex).or_default().pushed = seq;
    local.save()?;
    Ok(json!({ "applied": applied, "posted": posted, "done": done, "refused": refused, "marks": local.to_write() }))
}

/// A game's saves left in the share for the computer, when they changed
/// since it last had them (`savesync::post_to_share`).
fn share_post_saves(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let peer = peer_of(args["peer"].as_str().unwrap_or_default())?;
    let outbox = path_arg(args, "outbox")?;
    let a: SavesArgs = serde_json::from_value(args.clone())?;
    let posted = savesync::post_to_share(&outbox, &key, &peer, &a.game, &a.roots(), &a.baseline)?;
    Ok(serde_json::to_value(posted)?)
}

// Shares --------------------------------------------------------------------
//
// The person's own shares that droidtop reaches itself, through lib-net's share
// client (Droidtop/tracker#573), named in `share`:
// `{"kind": "webdav", "url", "user", "password", "allow_plain_http"}`.

/// The share named in [`args`]' `share`, or None when there is none.
fn share_of(args: &Value) -> Result<Option<Box<dyn Remote>>, Failure> {
    let share = &args["share"];
    if share.is_null() {
        return Ok(None);
    }
    let text = |k: &str| share[k].as_str().filter(|v| !v.is_empty()).map(str::to_string);
    match share["kind"].as_str().unwrap_or_default() {
        "webdav" => {
            let url = text("url").ok_or_else(|| Failure::Coded("bad_address", "the share has no address".into()))?;
            let mut config = WebDavConfig::new(url).allow_plain_http(share["allow_plain_http"].as_bool().unwrap_or(false));
            if let Some(user) = text("user") {
                config = config.sign_in(user, text("password").unwrap_or_default());
            }
            Ok(Some(Box::new(WebDav::new(config).map_err(remote_failure)?)))
        }
        other => Err(Failure::Error(format!("droidtop does not know shares of kind {other:?}"))),
    }
}

/// A share's failure, with the code the settings row tells apart.
fn remote_failure(e: RemoteError) -> Failure {
    let message = e.to_string();
    match e {
        RemoteError::Unreachable(_) => Failure::Unreachable(message),
        RemoteError::SignInRefused => Failure::Coded("sign_in_refused", message),
        RemoteError::NotEncrypted => Failure::Coded("not_encrypted", message),
        RemoteError::BadPath(_) => Failure::Coded("bad_address", message),
        RemoteError::NotFound(_) => Failure::Coded("not_found", message),
        RemoteError::Refused(_) => Failure::Coded("refused", message),
        _ => Failure::Error(message),
    }
}

/// Whether a share answers with the sign-in given: its top folder is listed. `{"entries": n}`.
fn remote_test(args: &Value) -> Outcome {
    let share = share_of(args)?.ok_or_else(|| Failure::Error("no share was given".into()))?;
    let entries = share.list(&RemotePath::root()).map_err(remote_failure)?;
    Ok(json!({ "entries": entries.len() }))
}

fn remote_path(args: &Value, name: &str) -> Result<RemotePath, Failure> {
    RemotePath::new(args[name].as_str().unwrap_or_default()).map_err(remote_failure)
}

/// What is in the share's folder `dir`: `{"entries": [{"name", "is_dir", "size", "mtime_ms"}]}`, empty when the
/// folder is not there.
fn remote_list(args: &Value) -> Outcome {
    let share = share_of(args)?.ok_or_else(|| Failure::Error("no share was given".into()))?;
    let entries = match share.list(&remote_path(args, "dir")?) {
        Ok(entries) => entries,
        Err(RemoteError::NotFound(_)) => Vec::new(),
        Err(e) => return Err(remote_failure(e)),
    };
    let entries: Vec<Value> =
        entries.iter().map(|e| json!({ "name": e.name, "is_dir": e.is_dir, "size": e.size, "mtime_ms": e.mtime_ms })).collect();
    Ok(json!({ "entries": entries }))
}

/// Sealed with this device's own key: what droidtop writes to a share the person keeps locked
/// (Droidtop/tracker#573, owner 2026-11-05: "Encryption should probably be toggleable"). The agent's
/// sealing (`mailbox::seal`), addressed to this device itself, so only this device opens it.
fn seal_to_self(args: &Value, bytes: &[u8]) -> Result<Vec<u8>, Failure> {
    let key = key_of(args)?;
    Ok(mailbox::seal(&key, &key.peer_id(), bytes)?)
}

/// Writes the local `file` to the share at `path`, its folders made first; sealed to this device when `seal` is
/// true (`seed` is then this device's key). `{"bytes": n}`, what went to the share.
fn remote_put(args: &Value) -> Outcome {
    let share = share_of(args)?.ok_or_else(|| Failure::Error("no share was given".into()))?;
    let path = remote_path(args, "path")?;
    let plain = std::fs::read(path_arg(args, "file")?).map_err(|e| Failure::Error(format!("the file to send could not be read ({e})")))?;
    let bytes = if args["seal"].as_bool().unwrap_or(false) { seal_to_self(args, &plain)? } else { plain };
    if let Some(parent) = path.parent().filter(|p| !p.is_root()) {
        share.make_dirs(&parent).map_err(remote_failure)?;
    }
    share.write(&path, &bytes).map_err(remote_failure)?;
    Ok(json!({ "bytes": bytes.len() }))
}

/// Reads `path` from the share into the local `file` (written under a temporary name, then renamed). A file this
/// device sealed is opened with `seed`; one sealed by another device is refused. `{"bytes": n, "sealed": bool}`.
fn remote_get(args: &Value) -> Outcome {
    let share = share_of(args)?.ok_or_else(|| Failure::Error("no share was given".into()))?;
    let bytes = share.read(&remote_path(args, "path")?).map_err(remote_failure)?;
    let sealed = bytes.starts_with(b"DTM1");
    let plain = if sealed {
        let key = key_of(args)?;
        let me = key.peer_id();
        match mailbox::open(&key, &bytes, |p| *p == me) {
            Ok((_, plain)) => plain,
            Err(_) => {
                return Err(Failure::Coded("locked_elsewhere", "this was locked by another device, so this device can't open it".into()))
            }
        }
    } else {
        bytes
    };
    let file = path_arg(args, "file")?;
    let tmp = file.with_extension("part");
    std::fs::write(&tmp, &plain)
        .and_then(|_| std::fs::rename(&tmp, &file))
        .map_err(|e| Failure::Error(format!("the file could not be saved on this device ({e})")))?;
    Ok(json!({ "bytes": plain.len(), "sealed": sealed }))
}

// Game folders ----------------------------------------------------------------
//
// A version of a game copied between this device and a computer, as a new
// folder beside the game's others (droidtop-agent docs/DESIGN.md section 7,
// "Game updates"). droidtop runs it as a job; how far it has come is written
// to the file named `progress` (`{"done": .., "total": ..}`) at most twice a
// second.

/// Writes how far a copy has come, now and then.
fn progress_file(path: Option<PathBuf>) -> impl FnMut(u64, u64) {
    let mut last = std::time::Instant::now() - Duration::from_secs(1);
    move |done, total| {
        let Some(path) = &path else { return };
        if done < total && last.elapsed() < Duration::from_millis(500) {
            return;
        }
        last = std::time::Instant::now();
        let _ = std::fs::write(path, json!({ "done": done, "total": total }).to_string());
    }
}

fn game_pull(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let game: GameRef = serde_json::from_value(args["game"].clone())?;
    let parent = path_arg(args, "parent")?;
    let name = args["folder_name"].as_str().unwrap_or_default().to_string();
    let mut progress = progress_file(args["progress"].as_str().map(PathBuf::from));
    let mut s = connect(&key, args)?;
    let copied = droidtop_agent_core::gamecopy::pull(&mut s.ch, &game, &parent, &name, &mut progress);
    let v = located(serde_json::to_value(copied?)?, &s);
    bye(s);
    Ok(v)
}

fn game_push(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let game: GameRef = serde_json::from_value(args["game"].clone())?;
    let source = path_arg(args, "source")?;
    let name = args["folder_name"].as_str().unwrap_or_default().to_string();
    let version = args["version"].as_str().map(str::to_string);
    let mut progress = progress_file(args["progress"].as_str().map(PathBuf::from));
    let mut s = connect(&key, args)?;
    let copied = droidtop_agent_core::gamecopy::push(&mut s.ch, &game, &source, &name, version, &mut progress);
    let v = located(serde_json::to_value(copied?)?, &s);
    bye(s);
    Ok(v)
}

// Starting a game on the computer ---------------------------------------------

/// Asks the computer to start a game (droidtop-agent docs/DESIGN.md section
/// 17). The reply is `{"result": ...}` with `started` (and a `stream` of
/// `windowcast` when the game is the computer's own and windowcast shows it;
/// none for Steam Link), `not_allowed`, `unknown_game`, `steam_not_running`,
/// `failed` (and a `message`) or
/// `agent_too_old`, for an agent that does not state the `launch` feature: it
/// is not sent a request it cannot read.
///
/// With `stream` (`{"via": "windowcast", "viewer": <hex>}`) droidtop also
/// asks to watch the game (section 22): `started` then carries `stream:
/// windowcast`. An agent that does not state the `stream` feature (too old,
/// or a computer whose agent hosts no stream, such as a Mac) is not asked to
/// start anything and the reply is `cannot_stream`.
fn launch_game(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let game: GameRef = serde_json::from_value(args["game"].clone())?;
    let stream: Option<StreamAsk> = if args["stream"].is_null() { None } else { Some(serde_json::from_value(args["stream"].clone())?) };
    let mut s = connect(&key, args)?;
    let has = |feature: &str| s.features.iter().any(|f| f == feature);
    let outcome = if !has("launch") {
        json!({ "result": "agent_too_old" })
    } else if stream.is_some() && !has("stream") {
        json!({ "result": "cannot_stream" })
    } else {
        s.ch.send_json(&Request::LaunchGame { game, stream })?;
        match s.ch.recv_json::<Response>()? {
            Response::Launch { outcome } => serde_json::to_value(outcome)?,
            Response::Error { message, .. } => return Err(Failure::Error(message)),
            other => return Err(Failure::Error(format!("the computer answered {other:?}"))),
        }
    };
    let v = located(outcome, &s);
    bye(s);
    Ok(v)
}

// Announcements and handheld peers --------------------------------------------

/// Tells the paired device (a computer, or a handheld that serves) every
/// plugin's value this device announces (`values`, in place of what it
/// announced before) and answers with the device's own (`announced`).
fn announce(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let values: Announcements = serde_json::from_value(args["values"].clone()).unwrap_or_default();
    check_announcements(&values).map_err(Failure::Error)?;
    let mut s = connect(&key, args)?;
    if !s.features.iter().any(|f| f == "announce") {
        bye(s);
        return Err(Failure::Error(
            "the other device runs an older droidtop-agent or droidtop; update it to share plugins' devices".into(),
        ));
    }
    s.ch.send_json(&Request::Announce { values })?;
    let theirs = match s.ch.recv_json::<Response>()? {
        Response::Announced { values } => values,
        Response::Error { message, .. } => return Err(Failure::Error(message)),
        other => return Err(Failure::Error(format!("the other device answered {other:?}"))),
    };
    let v = located(json!({ "announced": theirs }), &s);
    bye(s);
    Ok(v)
}

/// A handheld serving the handhelds paired with it: what it answers.
/// Announcements now; save sync between handhelds follows
/// (Droidtop/tracker#546).
struct HandheldHost {
    name: String,
    values: Announcements,
    /// Where what each peer announced is kept, by peer id: `{values, ms}`.
    state: PathBuf,
    writing: Mutex<()>,
}

impl droidtop_agent_core::server::Host for HandheldHost {
    fn name(&self) -> String {
        self.name.clone()
    }
    fn offers(&self, _peer: &PeerId) -> droidtop_agent_core::proto::Offer {
        droidtop_agent_core::proto::Offer::NONE
    }
    fn syncs_contexts(&self, _peer: &PeerId) -> bool {
        false
    }
    fn saves(&self, _game: &GameRef) -> Option<(droidtop_agent_core::saves::SaveSpec, Roots)> {
        None
    }
    fn archive_dir(&self, _game: &GameRef) -> PathBuf {
        std::env::temp_dir()
    }
    fn library_pull(&self, _peer: &PeerId, _since: u64) -> droidtop_agent_core::Result<(Vec<Change>, u64)> {
        Err(Error::Protocol("a handheld does not share its library with other handhelds yet".into()))
    }
    fn library_push(&self, _peer: &PeerId, _changes: Vec<Change>) -> droidtop_agent_core::Result<()> {
        Err(Error::Protocol("a handheld does not share its library with other handhelds yet".into()))
    }
    fn context_pull(&self, _context: &str, _offer: Option<&AdapterOffer>) -> droidtop_agent_core::Result<Records> {
        Err(Error::Protocol("a handheld does not sync plugin contexts with other handhelds".into()))
    }
    fn context_push(
        &self,
        _context: &str,
        _changes: Vec<droidtop_agent_core::context::RecordChange>,
    ) -> droidtop_agent_core::Result<Option<String>> {
        Err(Error::Protocol("a handheld does not sync plugin contexts with other handhelds".into()))
    }
    fn announce(&self, peer: &PeerId, theirs: Announcements) -> droidtop_agent_core::Result<Announcements> {
        let _one = self.writing.locked();
        let mut all: serde_json::Map<String, Value> =
            std::fs::read(&self.state).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        all.insert(peer.to_hex(), json!({ "values": theirs, "ms": droidtop_agent_core::library::now_ms() }));
        let tmp = self.state.with_extension("tmp");
        std::fs::write(&tmp, serde_json::to_vec(&all)?).and_then(|()| std::fs::rename(&tmp, &self.state))?;
        Ok(self.values.clone())
    }
}

struct Serving {
    stop: Arc<AtomicBool>,
    listening: thread::JoinHandle<()>,
}

static SERVING: Mutex<Option<Serving>> = Mutex::new(None);

/// Serves the handhelds paired with this one (`peers`), until `serve_stop`:
/// sessions on the agent's port (or `port`), and answers to their LAN
/// queries. What each announces is written to `state` (by peer id, its
/// `values` and when, `ms`); they are answered with this device's own
/// (`values`). Called again, it starts over with the new arguments. The
/// only thing in this library that runs on its own, and only while droidtop
/// asks for it.
fn serve_start(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let name = args["name"].as_str().unwrap_or("droidtop").to_string();
    let peers: Vec<PeerId> = args["peers"]
        .as_array()
        .map(|a| a.iter().filter_map(|p| p.as_str()).filter_map(|p| PeerId::from_hex(p).ok()).collect())
        .unwrap_or_default();
    let values: Announcements = serde_json::from_value(args["values"].clone()).unwrap_or_default();
    check_announcements(&values).map_err(Failure::Error)?;
    let state = path_arg(args, "state")?;
    let port = args["port"].as_u64().map(|p| p as u16).unwrap_or(PORT);
    serve_stop()?;
    let listener = TcpListener::bind(("0.0.0.0", port)).map_err(|e| Failure::Error(format!("could not serve on port {port} ({e})")))?;
    let port = listener.local_addr().map_err(|e| Failure::Error(e.to_string()))?.port();
    listener.set_nonblocking(true).map_err(|e| Failure::Error(e.to_string()))?;
    let stop = Arc::new(AtomicBool::new(false));
    let peers = Arc::new(peers);
    // Found by the LAN query like a computer, when the port is free.
    if let Ok(sock) = UdpSocket::bind(("0.0.0.0", PORT)) {
        let (stop, peers, key, name) = (stop.clone(), peers.clone(), DeviceKey::from_seed(&key.seed())?, name.clone());
        thread::spawn(move || {
            let _ = discovery::answer_agent_queries(&sock, &key, &name, port, |p| peers.contains(p), &stop);
        });
    }
    let host = Arc::new(HandheldHost { name, values, state, writing: Mutex::new(()) });
    let seed = key.seed();
    let listening = {
        let stop = stop.clone();
        thread::spawn(move || {
            while !stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let (host, peers) = (host.clone(), peers.clone());
                        thread::spawn(move || {
                            // Without its timeouts a stalled peer would hold this thread for ever: drop the connection.
                            let timed = stream
                                .set_nonblocking(false)
                                .and_then(|()| stream.set_read_timeout(Some(Duration::from_secs(120))))
                                .and_then(|()| stream.set_write_timeout(Some(Duration::from_secs(120))));
                            if timed.is_err() {
                                return;
                            }
                            let Ok(key) = DeviceKey::from_seed(&seed) else { return };
                            if let Ok(mut ch) = Channel::accept(stream, &key, |p| peers.contains(p)) {
                                let _ = droidtop_agent_core::server::serve(&mut ch, &*host);
                            }
                        });
                    }
                    Err(_) => thread::sleep(Duration::from_millis(200)),
                }
            }
        })
    };
    *SERVING.locked() = Some(Serving { stop, listening });
    Ok(json!({ "port": port }))
}

fn serve_stop() -> Outcome {
    let serving = SERVING.locked().take();
    if let Some(serving) = serving {
        serving.stop.store(true, Ordering::Relaxed);
        // The port is free again once the listener is gone.
        let _ = serving.listening.join();
    }
    Ok(json!({}))
}

// Plugin contexts ------------------------------------------------------------

fn sync_context(args: &Value) -> Outcome {
    let key = key_of(args)?;
    let decl: ContextDecl = serde_json::from_value(args["decl"].clone())?;
    let device: Records = serde_json::from_value(args["device"].clone()).unwrap_or_default();
    let baseline: Records = serde_json::from_value(args["baseline"].clone()).unwrap_or_default();
    let mut s = connect(&key, args)?;
    let ch = &mut s.ch;
    // The adapter the plugin offers, for a computer that has none for this context yet.
    let adapter: Option<AdapterOffer> = serde_json::from_value(args["adapter"].clone()).ok();
    ch.send_json(&Request::ContextPull { context: decl.id.clone(), adapter })?;
    let theirs = match ch.recv_json::<Response>()? {
        Response::Context { records } => records,
        Response::Error { message, .. } => return Err(Failure::Error(message)),
        other => return Err(Failure::Error(format!("the computer answered {other:?}"))),
    };
    let m = merge(&decl, &device, &theirs, &baseline);
    let (deferred, message) = if m.to_computer.is_empty() {
        (false, None)
    } else {
        ch.send_json(&Request::ContextPush { context: decl.id.clone(), changes: m.to_computer.clone() })?;
        match ch.recv_json::<Response>()? {
            Response::ContextApplied { deferred, message } => (deferred, message),
            Response::Error { message, .. } => return Err(Failure::Error(message)),
            other => return Err(Failure::Error(format!("the computer answered {other:?}"))),
        }
    };
    let kept = if deferred { baseline_when_deferred(&m, &theirs) } else { m.baseline.clone() };
    let v = located(
        json!({
            "device": m.device,
            "baseline": kept,
            "conflicts": m.conflicts,
            "sent": m.to_computer.len(),
            "deferred": deferred,
            "message": message,
        }),
        &s,
    );
    bye(s);
    Ok(v)
}

#[cfg(test)]
mod restore_tests {
    use super::*;
    use std::fs;

    /// A game whose saves are `<base>/save.dat`, synced here once (its spec
    /// is beside the baseline), with PC's set kept when the handheld's won.
    #[test]
    fn a_kept_copy_comes_back_and_what_was_here_is_kept_first() {
        let root = std::env::temp_dir().join(format!("dta-restore-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let (base, archive) = (root.join("game"), root.join("archive"));
        fs::create_dir_all(&base).unwrap();
        fs::write(base.join("save.dat"), "handheld").unwrap();
        let baseline = root.join("baseline.json");
        let spec = droidtop_agent_core::saves::SaveSpec { patterns: vec!["<base>/save.dat".into()] };
        fs::write(savesync::spec_path(&baseline), serde_json::to_vec(&spec).unwrap()).unwrap();
        let pc_copy = savesync::copy_dir(&archive, "PC");
        fs::create_dir_all(pc_copy.join("base")).unwrap();
        fs::write(pc_copy.join("base").join("save.dat"), "pc").unwrap();

        let args = json!({
            "game": { "key": "title2:g", "title": "G" }, "base": base, "baseline": baseline, "archive": archive,
            "name": "Handheld", "device": "PC",
        });
        let reply: Value = serde_json::from_str(&call("restore_saves", &args.to_string())).unwrap();
        assert_eq!(reply, json!({ "restored": 1, "kept": 1 }), "{reply}");
        assert_eq!(fs::read_to_string(base.join("save.dat")).unwrap(), "pc");
        // What was here is this device's copy now, so the restore can be undone.
        let mine = savesync::copy_dir(&archive, "Handheld");
        assert_eq!(fs::read_to_string(mine.join("base").join("save.dat")).unwrap(), "handheld");

        // Undoing it: this device's own copy comes back, and is not kept over itself first.
        let undo = json!({ "game": args["game"], "base": base, "baseline": baseline, "archive": archive, "name": "Handheld", "device": "Handheld" });
        let reply: Value = serde_json::from_str(&call("restore_saves", &undo.to_string())).unwrap();
        assert_eq!(reply, json!({ "restored": 1, "kept": 0 }), "{reply}");
        assert_eq!(fs::read_to_string(base.join("save.dat")).unwrap(), "handheld");

        // Both devices' copies are listed, each by the name restore_saves takes.
        let listed: Value = serde_json::from_str(&call("kept_saves", &json!({ "archive": archive }).to_string())).unwrap();
        let mut devices: Vec<(String, u64)> =
            listed.as_array().unwrap().iter().map(|c| (c["device"].as_str().unwrap().to_string(), c["files"].as_u64().unwrap())).collect();
        devices.sort();
        assert_eq!(devices, [("Handheld".to_string(), 1), ("PC".to_string(), 1)], "{listed}");
        assert!(listed[0]["newest_ms"].as_i64().unwrap() > 0, "{listed}");
        let none: Value = serde_json::from_str(&call("kept_saves", &json!({ "archive": root.join("other") }).to_string())).unwrap();
        assert_eq!(none, json!([]));

        // No copy from a device, or no spec yet: refused, nothing touched.
        let none: Value = serde_json::from_str(&call(
            "restore_saves",
            &json!({ "game": args["game"], "base": base, "baseline": baseline, "archive": archive, "device": "Nobody" }).to_string(),
        ))
        .unwrap();
        assert!(none["error"].as_str().unwrap().contains("No copy from Nobody"), "{none}");
        fs::remove_file(savesync::spec_path(&baseline)).unwrap();
        let unsynced: Value = serde_json::from_str(&call("restore_saves", &args.to_string())).unwrap();
        assert!(unsynced["error"].as_str().unwrap().contains("never synced here"), "{unsynced}");
        fs::remove_dir_all(&root).unwrap();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use droidtop_agent_core::tunnel;

    /// One pairing screen at a time: the tests that open one take this.
    static SCREEN: Mutex<()> = Mutex::new(());

    #[test]
    fn identities_round_trip_through_json() {
        let made: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        let again: Value = serde_json::from_str(&call("identity_id", &json!({ "seed": made["seed"] }).to_string())).unwrap();
        assert_eq!(made["id"], again["id"]);
        assert_eq!(made["fingerprint"], again["fingerprint"]);
        assert_eq!(made["fingerprint"].as_str().unwrap().len(), 64);
        assert!(call("nope", "{}").contains("error"));
    }

    #[test]
    fn pairing_through_the_api_pins_both_sides() {
        let _screen = SCREEN.lock().unwrap_or_else(|e| e.into_inner());
        let handheld: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        let started: Value = serde_json::from_str(&call(
            "pair_start",
            &json!({ "seed": handheld["seed"], "name": "Handheld", "address": "127.0.0.1" }).to_string(),
        ))
        .unwrap();
        let uri = started["uri"].as_str().unwrap().to_string();
        let computer = thread::spawn(move || {
            let invite = PairInvite::parse(&uri).unwrap();
            let mut s = TcpStream::connect(invite.at.unwrap()).unwrap();
            pairing::pair_client(&mut s, &DeviceKey::generate(), "PC", Kind::Computer, &invite.code).unwrap()
        });
        let waited: Value = serde_json::from_str(&call("pair_wait", r#"{"timeout_ms": 10000}"#)).unwrap();
        let paired = computer.join().unwrap();
        assert_eq!(waited["name"], "PC");
        assert_eq!(waited["kind"], "computer");
        assert_eq!(waited["fingerprint"], json!(cert::fingerprint_hex(&PeerId::from_hex(waited["peer"].as_str().unwrap()).unwrap())));
        assert_eq!(paired.name, "Handheld");
        assert_eq!(paired.kind, Kind::Handheld);
        assert_eq!(paired.fingerprint, handheld["fingerprint"].as_str().unwrap());
        assert_eq!(paired.peer.to_hex(), handheld["id"].as_str().unwrap());
    }

    /// droidtop SPEC 7o: the handheld picks a computer from the list, both
    /// show one code, and both pin after both say it matches.
    #[test]
    fn pairing_by_comparing_the_code_both_screens_show() {
        let handheld: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let at = listener.local_addr().unwrap();
        let pc = DeviceKey::generate();
        let pc_id = pc.peer_id();
        let computer = thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let c = confirm::host_start(&mut s, &pc).unwrap();
            let code = c.code.clone();
            (c.finish(&mut s, &pc, "Pythia PC", Kind::Computer, true).unwrap(), code)
        });
        let started: Value = serde_json::from_str(&call(
            "compare_start",
            &json!({ "seed": handheld["seed"], "id": pc_id.to_hex(), "address": at.to_string() }).to_string(),
        ))
        .unwrap();
        let out: Value = serde_json::from_str(&call(
            "compare_finish",
            &json!({ "seed": handheld["seed"], "name": "Handheld", "same": true }).to_string(),
        ))
        .unwrap();
        let (paired, code) = computer.join().unwrap();
        assert_eq!(started["code"], json!(code), "{started}");
        assert_eq!(out["peer"], json!(pc_id.to_hex()), "{out}");
        assert_eq!(out["kind"], "computer");
        assert_eq!(out["address"], json!(format!("127.0.0.1:{PORT}")));
        assert_eq!(paired.kind, Kind::Handheld);
        assert_eq!(paired.peer.to_hex(), handheld["id"].as_str().unwrap());
    }

    #[test]
    fn pairing_with_the_code_the_computer_shows() {
        let handheld: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let at = listener.local_addr().unwrap();
        let pc = DeviceKey::generate();
        let pc_id = pc.peer_id();
        let computer = thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            pairing::pair_host(&mut s, &pc, "PC", Kind::Computer, "246810").unwrap()
        });
        let out: Value = serde_json::from_str(&call(
            "pair_connect",
            &json!({ "seed": handheld["seed"], "name": "Handheld", "address": at.to_string(), "code": "246 810" }).to_string(),
        ))
        .unwrap();
        let paired = computer.join().unwrap();
        assert_eq!(out["peer"], json!(pc_id.to_hex()), "{out}");
        assert_eq!(out["name"], "PC");
        assert_eq!(out["kind"], "computer");
        assert_eq!(out["fingerprint"], json!(cert::fingerprint_hex(&pc_id)));
        assert_eq!(out["address"], json!(format!("127.0.0.1:{PORT}")));
        assert_eq!(paired.name, "Handheld");
        assert_eq!(paired.peer.to_hex(), handheld["id"].as_str().unwrap());
    }

    /// A computer reachable only through its WireGuard endpoint, as one away
    /// from the LAN with a forwarded port or a global IPv6 address is.
    struct AwayHost {
        library: Mutex<Library>,
    }

    impl droidtop_agent_core::server::Host for AwayHost {
        fn name(&self) -> String {
            "Away PC".into()
        }
        fn endpoints(&self) -> Vec<String> {
            vec!["wg:203.0.113.7:47611".into()]
        }
        fn offers(&self, _peer: &PeerId) -> droidtop_agent_core::proto::Offer {
            droidtop_agent_core::proto::Offer::LIBRARY_AND_SAVES
        }
        fn syncs_contexts(&self, _peer: &PeerId) -> bool {
            true
        }
        fn saves(&self, _game: &GameRef) -> Option<(droidtop_agent_core::saves::SaveSpec, Roots)> {
            None
        }
        fn launch_game(
            &self,
            _peer: &PeerId,
            game: &GameRef,
            _stream: Option<&droidtop_agent_core::proto::StreamAsk>,
        ) -> droidtop_agent_core::proto::Launch {
            use droidtop_agent_core::proto::Launch;
            match game.key.as_str() {
                "steam:440" => Launch::Started { stream: None, address: None },
                "steam:1" => Launch::NotAllowed,
                _ => Launch::Failed { message: "unexpected game".into() },
            }
        }
        fn archive_dir(&self, _game: &GameRef) -> PathBuf {
            std::env::temp_dir()
        }
        fn library_pull(
            &self,
            _peer: &PeerId,
            since: u64,
        ) -> droidtop_agent_core::Result<(Vec<droidtop_agent_core::library::Change>, u64)> {
            Ok(self.library.locked().changes_since(since))
        }
        fn library_push(&self, _peer: &PeerId, changes: Vec<droidtop_agent_core::library::Change>) -> droidtop_agent_core::Result<()> {
            let mut lib = self.library.locked();
            changes.into_iter().for_each(|c| {
                lib.apply(c);
            });
            Ok(())
        }
        fn context_pull(
            &self,
            _context: &str,
            _offer: Option<&droidtop_agent_core::context::AdapterOffer>,
        ) -> droidtop_agent_core::Result<Records> {
            Ok(Records::new())
        }
        fn context_push(
            &self,
            _context: &str,
            _changes: Vec<droidtop_agent_core::context::RecordChange>,
        ) -> droidtop_agent_core::Result<Option<String>> {
            Ok(None)
        }
    }

    #[test]
    fn a_computer_only_its_wireguard_endpoint_reaches_still_syncs() {
        let handheld: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        let hh_id = PeerId::from_hex(handheld["id"].as_str().unwrap()).unwrap();
        let pc = DeviceKey::generate();
        let pc_id = pc.peer_id();
        let mut pc_lib = Library::default();
        pc_lib.update_device(
            &pc_id,
            "Away PC",
            vec![ScannedGame { key: "steam:1".into(), title: "Game".into(), platform: None, install: Default::default() }],
        );
        pc_lib.mark(&pc_id, "steam:1", "favourite", json!(true));
        let host: &'static AwayHost = Box::leak(Box::new(AwayHost { library: Mutex::new(pc_lib) }));
        let udp = UdpSocket::bind("127.0.0.1:0").unwrap();
        let port = udp.local_addr().unwrap().port();
        let stop: &'static AtomicBool = Box::leak(Box::new(AtomicBool::new(false)));
        let pc_seed = pc.seed();
        let server = thread::spawn(move || {
            tunnel::serve(
                &pc,
                udp,
                move || vec![hh_id],
                move |peer, mut stream| {
                    thread::spawn(move || {
                        let key = DeviceKey::from_seed(&pc_seed).unwrap();
                        stream.set_read_timeout(Some(Duration::from_secs(20)));
                        let mut ch = Channel::accept(stream, &key, |p| *p == peer).unwrap();
                        droidtop_agent_core::server::serve(&mut ch, host).unwrap();
                    });
                },
                stop,
                &mut tunnel::NoHooks,
            )
            .unwrap();
        });
        let state = std::env::temp_dir().join(format!("dta-away-{}.json", std::process::id()));
        let _ = std::fs::remove_file(&state);
        let out: Value = serde_json::from_str(&call(
            "sync_library",
            &json!({
                "seed": handheld["seed"],
                "peer": pc_id.to_hex(),
                // No LAN address answers; only the endpoint the computer gave last time.
                "addresses": ["127.0.0.1:1", format!("wg:127.0.0.1:{port}")],
                "state": state,
                "scan": [{ "key": "steam:1", "title": "Game", "install": { "installed": true } }],
                "marks": { "steam:1": { "favourite": false, "hidden": false } },
            })
            .to_string(),
        ))
        .unwrap();
        stop.store(true, Ordering::Relaxed);
        server.join().unwrap();
        let _ = std::fs::remove_file(&state);
        assert!(out.get("unreachable").is_none() && out.get("error").is_none(), "{out}");
        assert_eq!(out["computer"], "Away PC");
        assert!(out.get("address").is_none(), "a tunnel is not a LAN address to remember: {out}");
        assert_eq!(out["endpoints"], json!(["wg:203.0.113.7:47611"]));
        assert_eq!(out["path"], "wireguard");
        assert_eq!(out["pulled"], 2);
        // The computer's favourite is for droidtop to write; nothing was sent back.
        assert_eq!(out["marks"], json!({ "steam:1": { "favourite": true } }));
        assert_eq!(host.library.locked().games["steam:1"].installs.len(), 2);
    }

    /// What droidtop sees of a launch: the computer's typed answer, in the
    /// shape the Steam module reads, over a real session.
    #[test]
    fn a_launch_request_reaches_the_computer_and_comes_back_typed() {
        let handheld: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        let hh_id = PeerId::from_hex(handheld["id"].as_str().unwrap()).unwrap();
        let pc = DeviceKey::generate();
        let pc_id = pc.peer_id();
        let host: &'static AwayHost = Box::leak(Box::new(AwayHost { library: Mutex::new(Library::default()) }));
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let at = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            for _ in 0..3 {
                let (s, _) = listener.accept().unwrap();
                let mut ch = Channel::accept(s, &pc, |p| *p == hh_id).unwrap();
                droidtop_agent_core::server::serve(&mut ch, host).unwrap();
            }
        });
        let ask = |key: &str, stream: Value| -> Value {
            serde_json::from_str(&call(
                "launch_game",
                &json!({
                    "seed": handheld["seed"],
                    "peer": pc_id.to_hex(),
                    "addresses": [at.to_string()],
                    "game": { "key": key, "title": "Game" },
                    "stream": stream,
                })
                .to_string(),
            ))
            .unwrap()
        };
        let started = ask("steam:440", Value::Null);
        assert_eq!(started["result"], "started", "{started}");
        assert_eq!(started["computer"], "Away PC");
        assert_eq!(ask("steam:1", Value::Null)["result"], "not_allowed");
        // This computer states no `stream`: asked to watch, it is not asked to start anything.
        let watch = json!({ "via": "windowcast", "viewer": "ab".repeat(32) });
        assert_eq!(ask("steam:440", watch)["result"], "cannot_stream");
        server.join().unwrap();
    }

    /// Two handhelds pair with the same screens as a computer, then one
    /// serves and the other tells it its plugins' values and learns its own.
    #[test]
    fn two_handhelds_pair_and_announce_to_each_other() {
        let _screen = SCREEN.lock().unwrap_or_else(|e| e.into_inner());
        let a: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        let b: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        let started: Value = serde_json::from_str(&call(
            "pair_start",
            &json!({ "seed": a["seed"], "name": "Pocket A", "address": "127.0.0.1" }).to_string(),
        ))
        .unwrap();
        let waiting = thread::spawn(|| -> Value { serde_json::from_str(&call("pair_wait", r#"{"timeout_ms": 10000}"#)).unwrap() });
        let joined: Value = serde_json::from_str(&call(
            "pair_connect",
            &json!({ "seed": b["seed"], "name": "Pocket B", "code": started["uri"] }).to_string(),
        ))
        .unwrap();
        let waited = waiting.join().unwrap();
        assert_eq!((joined["name"].as_str(), joined["kind"].as_str()), (Some("Pocket A"), Some("handheld")), "{joined}");
        assert_eq!((waited["name"].as_str(), waited["kind"].as_str()), (Some("Pocket B"), Some("handheld")), "{waited}");
        assert_eq!(joined["fingerprint"], a["fingerprint"]);
        assert_eq!(waited["fingerprint"], b["fingerprint"]);

        // A serves its paired handheld B, on a free port for the test.
        let state = std::env::temp_dir().join(format!("dta-announced-{}.json", std::process::id()));
        let _ = std::fs::remove_file(&state);
        let served: Value = serde_json::from_str(&call(
            "serve_start",
            &json!({
                "seed": a["seed"], "name": "Pocket A", "peers": [b["id"]], "port": 0, "state": state,
                "values": { "syncthing": { "syncthing": "AAAAAAA-A" } },
            })
            .to_string(),
        ))
        .unwrap();
        let at = format!("127.0.0.1:{}", served["port"]);
        let out: Value = serde_json::from_str(&call(
            "announce",
            &json!({ "seed": b["seed"], "peer": a["id"], "addresses": [at], "values": { "syncthing": { "syncthing": "BBBBBBB-B" } } })
                .to_string(),
        ))
        .unwrap();
        assert_eq!(out["announced"], json!({ "syncthing": { "syncthing": "AAAAAAA-A" } }), "{out}");
        assert_eq!(out["computer"], "Pocket A");
        let kept: Value = serde_json::from_slice(&std::fs::read(&state).unwrap()).unwrap();
        assert_eq!(kept[b["id"].as_str().unwrap()]["values"], json!({ "syncthing": { "syncthing": "BBBBBBB-B" } }));

        // A device A did not pair with is refused.
        let stranger: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        let refused: Value = serde_json::from_str(&call(
            "announce",
            &json!({ "seed": stranger["seed"], "peer": a["id"], "addresses": [at], "values": {} }).to_string(),
        ))
        .unwrap();
        assert!(refused.get("announced").is_none(), "{refused}");
        // Too large a value is refused before anything is sent.
        let big: Value = serde_json::from_str(&call(
            "announce",
            &json!({ "seed": b["seed"], "peer": a["id"], "addresses": [at], "values": { "x": "y".repeat(5000) } }).to_string(),
        ))
        .unwrap();
        assert!(big["error"].as_str().unwrap().contains("4096"), "{big}");
        call("serve_stop", "{}");
        let _ = std::fs::remove_file(&state);
    }

    #[test]
    fn a_computer_that_forgot_this_device_says_so() {
        let handheld: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        let pc = DeviceKey::generate();
        let pc_id = pc.peer_id();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let at = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (s, _) = listener.accept().unwrap();
            // It trusts nobody: this handheld was unpaired there.
            assert!(Channel::accept(s, &pc, |_| false).is_err());
        });
        let out: Value = serde_json::from_str(&call(
            "launch_game",
            &json!({ "seed": handheld["seed"], "peer": pc_id.to_hex(), "addresses": [at.to_string()], "game": { "key": "steam:440", "title": "Game" } })
                .to_string(),
        ))
        .unwrap();
        server.join().unwrap();
        assert_eq!(out["code"], "not_paired", "{out}");
        assert!(out["error"].as_str().unwrap().contains("pair it again"), "{out}");
        assert!(out.get("unreachable").is_none(), "{out}");
    }

    #[test]
    fn an_unreachable_computer_says_so() {
        let handheld: Value = serde_json::from_str(&call("identity_new", "{}")).unwrap();
        let pc = DeviceKey::generate().peer_id().to_hex();
        let out: Value = serde_json::from_str(&call(
            "sync_library",
            &json!({ "seed": handheld["seed"], "peer": pc, "addresses": ["127.0.0.1:1"], "state": std::env::temp_dir().join("dta-unreachable.json"), "scan": [] }).to_string(),
        ))
        .unwrap();
        assert!(out.get("unreachable").is_some(), "{out}");
    }
}
