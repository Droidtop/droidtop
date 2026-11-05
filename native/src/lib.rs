//! droidtop's side of droidtop-agent (docs/DESIGN.md section 12): the core
//! behind a single JNI entry point, `dev.droidtop.net.peer.AgentNative.nativeCall`,
//! which takes an operation name and its arguments as JSON and returns JSON.
//! Every operation blocks the calling thread; droidtop calls it from a
//! background coroutine, never the main thread. Nothing here runs on its
//! own: there is no resident thread, listener or timer.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

pub mod api;

/// `AgentNative.nativeCall(op: String, args: String): String`, a static method.
#[no_mangle]
pub extern "system" fn Java_dev_droidtop_net_peer_AgentNative_nativeCall<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    op: JString<'local>,
    args: JString<'local>,
) -> jstring {
    let op: String = env.get_string(&op).map(Into::into).unwrap_or_default();
    let args: String = env.get_string(&args).map(Into::into).unwrap_or_default();
    let out = std::panic::catch_unwind(|| api::call(&op, &args)).unwrap_or_else(|_| api::error("the agent library stopped unexpectedly"));
    match env.new_string(out) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
