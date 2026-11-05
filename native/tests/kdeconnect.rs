//! droidtop's KDE Connect operations reach lib-kdeconnect's table through the one JNI entry point
//! (docs/SPEC.md "KDE Connect").

use droidtop_agent::api::call;
use serde_json::Value;

fn reply(op: &str, args: &str) -> Value {
    serde_json::from_str(&call(op, args)).unwrap()
}

#[test]
fn kdeconnect_operations_pass_through() {
    assert!(reply("kdeconnect_devices", "{}")["error"].as_str().unwrap().contains("not on"));
    assert!(reply("kdeconnect_nope", "{}")["error"].as_str().unwrap().contains("unknown KDE Connect operation"));
    let dir = std::env::temp_dir().join(format!("dt-kdc-{}", std::process::id()));
    let started =
        reply("kdeconnect_start", &serde_json::json!({"state_dir": dir, "name": "Retroid Pocket 5", "type": "phone"}).to_string());
    if let Some(e) = started["error"].as_str() {
        // UDP 1716 is taken where an official KDE Connect app runs on the test machine.
        assert!(e.contains("Another KDE Connect"), "{e}");
        return;
    }
    assert_eq!(started["name"], "Retroid Pocket 5");
    assert!(reply("kdeconnect_devices", "{}")["devices"].is_array());
    assert_eq!(reply("kdeconnect_event", r#"{"timeout_ms": 10}"#)["event"], "none");
    assert_eq!(reply("kdeconnect_stop", "{}"), serde_json::json!({}));
    let _ = std::fs::remove_dir_all(dir);
}
