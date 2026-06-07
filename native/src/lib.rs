use std::sync::atomic::{AtomicBool, Ordering};
use std::net::TcpListener;
use std::thread;

use jni::objects::JString;
use jni::JNIEnv;
use jni::sys::{jlong, JNI_VERSION_1_6};

static RUNNING: AtomicBool = AtomicBool::new(false);

#[no_mangle]
pub extern "system" fn JNI_OnLoad(_: *mut std::ffi::c_void, _: *mut std::ffi::c_void) -> i32 {
    android_logger::init_once(android_logger::Config::default().with_min_level(log::Level::Info));
    log::info!("ZeroClaw JNI bridge loaded");
    JNI_VERSION_1_6
}

#[no_mangle]
pub extern "system" fn Java_com_kaonixx_zeroclaw_ZeroClawService_nativeStartAgent(
    mut env: JNIEnv,
    _: jni::objects::JObject,
    config_dir: JString,
    port: jni::sys::jint,
) -> jlong {
    let config_dir: String = match env.get_string(&config_dir) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    log::info!("Starting agent: config={}, port={}", config_dir, port);
    RUNNING.store(true, Ordering::SeqCst);
    let p = port as u16;
    let handle = thread::spawn(move || {
        if let Ok(listener) = TcpListener::bind(format!("127.0.0.1:{}", p)) {
            log::info!("Gateway listening on 127.0.0.1:{}", p);
            for stream in listener.incoming() {
                if !RUNNING.load(Ordering::SeqCst) { break; }
                if let Ok(_) = stream { log::debug!("Connection accepted"); }
            }
        }
    });
    handle.as_thread().id() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_kaonixx_zeroclaw_ZeroClawService_nativeStopAgent(
    _: JNIEnv,
    _: jni::objects::JObject,
) {
    log::info!("Stopping agent");
    RUNNING.store(false, Ordering::SeqCst);
}
