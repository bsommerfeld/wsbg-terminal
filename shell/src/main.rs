#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
//! WSBG Terminal shell.
//!
//! One native window around the system webview (WKWebView on macOS, WebView2 on
//! Windows, WebKitGTK on Linux) showing the terminal's web UI, which the Java
//! backend serves on the loopback interface. The backend is started as a
//! sidecar and told to quit by closing its stdin when the shell exits.
//!
//! Arguments:
//!   --url <http://127.0.0.1:...>   show this page (backend started elsewhere)
//!   --backend <cmd> [args...]      start the backend, read its WSBG_ENTRY_URL= line
//!   --init-script <file>           inject this JavaScript before every page load
//!   --size <w>x<h>                 window size (logical px, default 1280x820)
//!   --pos <x>,<y>                  window position (default: centred)
//!   --limit-60                     macOS: start with WebKit's ~60 fps cap on page rendering
//!                                  updates (default: follow the display's refresh rate); the
//!                                  backend's `frame-rate=display|60` command sets it for the
//!                                  next page load - WebKit reads the cap at page creation
//!
//! The backend's stdout is the control channel back: `WSBG_ENTRY_URL=` announces the
//! page, `WSBG_SHELL_CMD=close|minimize|maximize-toggle|drag-start|raise` are the page's
//! window buttons, its caption drag (Windows) and the raise request of a second
//! launch, `WSBG_SHELL_CMD=frame-rate=display|60` the user's redraw-rate setting;
//! everything else is its log.
//!
//! Per platform: macOS keeps the native title bar (transparent, the page draws
//! under the traffic lights); Windows gets no OS decorations, the page's own bar
//! is the caption; Linux keeps the OS title bar and the page hides its own.

use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tauri::{RunEvent, WebviewUrl, WebviewWindowBuilder};

#[derive(Default)]
struct Options {
    url: Option<String>,
    backend: Vec<String>,
    init_script: Option<String>,
    size: (f64, f64),
    pos: Option<(f64, f64)>,
    limit60: bool,
}

fn parse_args() -> Options {
    let mut o = Options { size: (1280.0, 820.0), ..Default::default() };
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "--url" => o.url = it.next(),
            "--init-script" => o.init_script = it.next(),
            "--size" => {
                if let Some(v) = it.next() {
                    if let Some((w, h)) = v.split_once('x') {
                        o.size = (w.parse().unwrap_or(1280.0), h.parse().unwrap_or(820.0));
                    }
                }
            }
            "--pos" => {
                if let Some(v) = it.next() {
                    if let Some((x, y)) = v.split_once(',') {
                        o.pos = Some((x.parse().unwrap_or(0.0), y.parse().unwrap_or(0.0)));
                    }
                }
            }
            "--backend" => {
                o.backend = it.by_ref().collect();
            }
            "--limit-60" => o.limit60 = true,
            _ => eprintln!("[shell] ignoring unknown argument {a}"),
        }
    }
    o
}

/// The sidecar backend: its process and the stdin pipe whose EOF is its quit signal.
struct Backend {
    child: Child,
    stdin: Option<ChildStdin>,
}

type BackendReader = BufReader<std::process::ChildStdout>;

/// Starts the backend and blocks until it announces its entry URL. The reader is
/// handed back so the relay (which needs the app handle) can take over the stream.
fn start_backend(cmd: &[String]) -> Result<(Backend, String, BackendReader), String> {
    let mut child = Command::new(&cmd[0])
        .args(&cmd[1..])
        .env("WSBG_SHELL", "external")
        .env("WSBG_SHELL_PARENT_WATCH", "true")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| format!("cannot start backend {:?}: {e}", cmd[0]))?;
    let stdin = child.stdin.take();
    let stdout = child.stdout.take().ok_or("backend stdout not piped")?;
    let mut reader = BufReader::new(stdout);
    let started = Instant::now();
    let mut line = String::new();
    let url = loop {
        line.clear();
        let n = reader.read_line(&mut line).map_err(|e| format!("backend stdout: {e}"))?;
        if n == 0 {
            return Err("backend exited before announcing its entry URL".into());
        }
        print!("{line}");
        if let Some(rest) = line.trim().strip_prefix("WSBG_ENTRY_URL=") {
            break rest.trim().to_string();
        }
        if started.elapsed() > Duration::from_secs(120) {
            return Err("backend did not announce its entry URL within 120 s".into());
        }
    };
    Ok((Backend { child, stdin }, url, reader))
}

/// Relays the backend's stdout to ours and executes its window commands.
fn relay_backend_output(mut reader: BackendReader, app: tauri::AppHandle) {
    std::thread::spawn(move || {
        let mut line = String::new();
        while let Ok(n) = reader.read_line(&mut line) {
            if n == 0 {
                break;
            }
            if let Some(cmd) = line.trim().strip_prefix("WSBG_SHELL_CMD=") {
                window_command(&app, cmd.trim());
            } else {
                print!("{line}");
            }
            line.clear();
        }
    });
}

/// The page's title-bar buttons and the raise request, applied to the one window.
fn window_command(app: &tauri::AppHandle, cmd: &str) {
    use tauri::Manager;
    let Some(w) = app.get_webview_window("main") else { return };
    println!("[shell] window command: {cmd}");
    if let Some(rate) = cmd.strip_prefix("frame-rate=") {
        if let Err(e) = apply_frame_rate(&w, rate) {
            eprintln!("[shell] frame-rate {rate} failed: {e}");
        }
        return;
    }
    let result = match cmd {
        "close" => w.close(),
        "drag-start" => w.start_dragging(),
        "minimize" => w.minimize(),
        "maximize-toggle" => match w.is_maximized() {
            Ok(true) => w.unmaximize(),
            Ok(false) => w.maximize(),
            Err(e) => Err(e),
        },
        "raise" => w.unminimize().and_then(|_| w.show()).and_then(|_| w.set_focus()),
        other => {
            eprintln!("[shell] unknown window command {other}");
            Ok(())
        }
    };
    if let Err(e) = result {
        eprintln!("[shell] window command {cmd} failed: {e}");
    }
}

/// Closes the backend's stdin (its quit signal) and waits for it to exit.
fn stop_backend(b: &mut Backend) {
    if let Some(mut stdin) = b.stdin.take() {
        let _ = stdin.flush();
        drop(stdin);
    }
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        match b.child.try_wait() {
            Ok(Some(status)) => {
                println!("[shell] backend exited: {status}");
                return;
            }
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(100)),
            _ => {
                eprintln!("[shell] backend did not exit in time - killing it");
                let _ = b.child.kill();
                let _ = b.child.wait();
                return;
            }
        }
    }
}

/// WebKit caps page rendering updates near 60 fps by default (a power setting on
/// WKPreferences). On a 120/180 Hz display the terminal's scrolling and JS-driven
/// motion would be stuck at 60, so the shell lifts the cap unless the user asks
/// for the lighter 60 (settings, "Bildrate"). Best-effort through the private
/// feature list: if a future WebKit renames it, nothing is sent and the default
/// stays.
#[cfg(target_os = "macos")]
unsafe fn set_60fps_cap(webview: *mut std::ffi::c_void, capped: bool) {
    use objc2::runtime::{AnyClass, AnyObject, Bool, Sel};
    use objc2::{msg_send, sel};
    let wk: &AnyObject = &*(webview as *const AnyObject);
    let config: *mut AnyObject = msg_send![wk, configuration];
    if config.is_null() {
        return;
    }
    let prefs: *mut AnyObject = msg_send![&*config, preferences];
    if prefs.is_null() {
        return;
    }
    let flag = Bool::new(capped);
    // Older WebKit: a private property on WKPreferences.
    let setter: Sel = sel!(_setPreferPageRenderingUpdatesNear60FPSEnabled:);
    let responds: Bool = msg_send![&*prefs, respondsToSelector: setter];
    if responds.as_bool() {
        let _: () = msg_send![&*prefs, _setPreferPageRenderingUpdatesNear60FPSEnabled: flag];
        println!("[shell] WebKit 60 fps cap {} (preference)", if capped { "on" } else { "off" });
        return;
    }
    // Newer WebKit: the setting is one of the private feature flags, addressed by key.
    let Some(cls) = AnyClass::get(c"WKPreferences") else { return };
    let mut found = false;
    for list in [sel!(_features), sel!(_internalDebugFeatures), sel!(_experimentalFeatures)] {
        let responds: Bool = msg_send![cls, respondsToSelector: list];
        if !responds.as_bool() {
            continue;
        }
        let features: *mut AnyObject = msg_send![cls, performSelector: list];
        if features.is_null() {
            continue;
        }
        let n: usize = msg_send![&*features, count];
        for i in 0..n {
            let f: *mut AnyObject = msg_send![&*features, objectAtIndex: i];
            let key: *mut AnyObject = msg_send![&*f, key];
            if key.is_null() {
                continue;
            }
            let cstr: *const std::ffi::c_char = msg_send![&*key, UTF8String];
            let k = std::ffi::CStr::from_ptr(cstr).to_string_lossy();
            if k.contains("60FPS") || k.contains("Near60") {
                let _: () = msg_send![&*prefs, _setEnabled: flag, forFeature: &*f];
                found = true;
            }
        }
    }
    if found {
        println!("[shell] WebKit 60 fps cap {} (feature)", if capped { "on" } else { "off" });
    } else {
        eprintln!("[shell] WebKit preference for the 60 fps cap not available");
    }
}

/// Applies the user's redraw-rate choice to the window's webview.
fn apply_frame_rate(w: &tauri::WebviewWindow, value: &str) -> tauri::Result<()> {
    let capped = value.trim() == "60";
    #[cfg(target_os = "macos")]
    {
        return w.with_webview(move |wv| unsafe { set_60fps_cap(wv.inner(), capped) });
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (w, capped); // WebView2 / WebKitGTK render at display rate; nothing to cap.
        Ok(())
    }
}

/// What the page may know about its host: an always-present init script, so the
/// settings view can show the redraw-rate row only where the cap exists and the
/// title bar can act as the caption on Windows.
fn host_marker_script() -> String {
    let platform = if cfg!(target_os = "macos") {
        "macos"
    } else if cfg!(target_os = "windows") {
        "windows"
    } else {
        "linux"
    };
    format!(
        "window.__WSBG_SHELL__ = Object.freeze({{ platform: \"{platform}\", frameRateCap: {} }});",
        cfg!(target_os = "macos")
    )
}

fn main() {
    let opts = parse_args();
    let backend: Arc<Mutex<Option<Backend>>> = Arc::new(Mutex::new(None));
    let backend_reader: Arc<Mutex<Option<BackendReader>>> = Arc::new(Mutex::new(None));

    if opts.url.is_none() && opts.backend.is_empty() {
        eprintln!("[shell] need --url <url> or --backend <cmd...>");
        std::process::exit(2);
    }

    let init_script = opts
        .init_script
        .as_deref()
        .map(|p| std::fs::read_to_string(p).unwrap_or_else(|e| {
            eprintln!("[shell] cannot read init script {p}: {e}");
            String::new()
        }));

    let size = opts.size;
    let pos = opts.pos;
    let limit60 = opts.limit60;
    let opts_url = opts.url.clone();
    let opts_backend = opts.backend.clone();
    let backend_for_setup = backend.clone();
    let reader_for_setup = backend_reader.clone();
    let app = tauri::Builder::default()
        // A second launch focuses the running window instead of starting twice
        // (the backend's own instance lock stays as the fallback behind it).
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            window_command(app, "raise");
        }))
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .setup(move |app| {
            // The backend starts here, behind the single-instance check: a second
            // launch must never boot a second JVM just to find the lock taken.
            let url = if let Some(u) = opts_url.clone() {
                u
            } else {
                let (b, url, reader) = start_backend(&opts_backend).map_err(|e| {
                    eprintln!("[shell] {e}");
                    e
                })?;
                *backend_for_setup.lock().unwrap() = Some(b);
                *reader_for_setup.lock().unwrap() = Some(reader);
                url
            };
            println!("[shell] entry URL: {url}");
            let parsed = url.parse().map_err(|e| format!("bad entry URL {url}: {e}"))?;
            let mut b = WebviewWindowBuilder::new(app, "main", WebviewUrl::External(parsed))
                .title("WSBG Terminal")
                .inner_size(size.0, size.1)
                .min_inner_size(800.0, 600.0);
            if let Some((x, y)) = pos {
                b = b.position(x, y);
            } else {
                b = b.center();
            }
            b = b.initialization_script(&host_marker_script());
            if let Some(script) = init_script.as_deref() {
                if !script.is_empty() {
                    b = b.initialization_script(script);
                }
            }
            #[cfg(target_os = "macos")]
            {
                // The page draws its own title bar flush under the native traffic
                // lights - the same arrangement the Swing window had.
                b = b.title_bar_style(tauri::TitleBarStyle::Overlay).hidden_title(true);
            }
            #[cfg(target_os = "windows")]
            {
                // No OS caption: the page's title bar is the caption (its buttons
                // and drag arrive as window commands). Shadow and resizable edges
                // stay with the borderless window.
                b = b.decorations(false).shadow(true);
            }
            // Linux keeps the OS title bar; the page hides its own there.
            let window = b.build()?;
            apply_frame_rate(&window, if limit60 { "60" } else { "display" })?;
            // Size and position come back from the last session.
            {
                use tauri_plugin_window_state::{StateFlags, WindowExt};
                if let Err(e) = window.restore_state(StateFlags::all()) {
                    eprintln!("[shell] window state not restored: {e}");
                }
            }
            if let Some(reader) = reader_for_setup.lock().unwrap().take() {
                relay_backend_output(reader, app.handle().clone());
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("failed to build the shell");

    let backend_on_exit = backend.clone();
    app.run(move |_handle, event| {
        if let RunEvent::Exit = event {
            if let Some(mut b) = backend_on_exit.lock().unwrap().take() {
                stop_backend(&mut b);
            }
        }
    });
}
