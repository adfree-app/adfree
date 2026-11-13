//! The other half of the store button: a loopback endpoint the injected page
//! calls when someone clicks "Cài đặt" beside Greasy Fork's own install link.
//!
//! The button is not in the app: the page is rewritten in flight (see
//! [`crate::mitm::decide`]), so the browser shows an extra control that installs,
//! updates or removes a userscript the way a store page does. The page still
//! runs on `greasyfork.org`, so the only way back into the app is a request,
//! and a request needs a surface to arrive on. That surface is here: a socket on
//! `127.0.0.1` that answers nothing at all without the token the injection
//! carries.
//!
//! Why a token instead of a custom URL scheme: registering `adfree://` means an
//! OS-level handler that any page can aim at, and the app would have to guess
//! from a URL whether the click was the button's. The token is minted per run,
//! never leaves the machine, and is only written into pages served to the store
//! hosts below — a page anywhere else has nothing to present.

use crate::state::model::ScriptRecord;
use std::io::{Read as _, Write as _};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

/// Where the button is injected. Greasy Fork and its NSFW sibling share a
/// layout, so one injector covers both; OpenUserJS and ScriptCat use their own
/// and are not wired yet.
const STORE_HOSTS: &[&str] = &["greasyfork.org", "sleazyfork.org"];

/// The asset path the injected page fetches its code from.
pub const STORE_SCRIPT_PATH: &str = "/adfree-store.js";

/// Bound on one request: headers, then a body that is only ever a small JSON
/// object naming a URL.
const MAX_REQUEST: usize = 8 * 1024;

/// How long a click may take to answer. A second install of the same script is
/// a second download, so this is the download's budget plus the write.
const MAX_CONNECTIONS: usize = 8;

/// Bound on one downloaded userscript body.
const MAX_SCRIPT_BYTES: u64 = 4 * 1024 * 1024;

static RUN: Mutex<Option<Running>> = Mutex::new(None);

/// Bumped whenever the store changes the script list, so a UI that keeps its
/// own copy knows to re-read it. The app is the one place the list lives; this
/// is only the signal that it moved.
static REVISION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
static TOKEN: OnceLock<String> = OnceLock::new();

struct Running {
    port: u16,
    stop: Arc<AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}

/// The per-run secret the injected page presents. Minted once, kept in this
/// process, and never written anywhere but the pages the store hosts receive.
fn token() -> &'static str {
    TOKEN.get_or_init(crate::mitm::http::new_nonce)
}

/// Whether `host` is a store page the button belongs on.
pub fn is_store_host(host: &str) -> bool {
    let host = host.trim_start_matches("www.").to_ascii_lowercase();
    STORE_HOSTS
        .iter()
        .any(|candidate| host == *candidate || host.ends_with(&format!(".{candidate}")))
}

/// The port the endpoint listens on, or 0 when it is not running.
pub fn port() -> u16 {
    match RUN.lock() {
        Ok(slot) => slot.as_ref().map(|run| run.port).unwrap_or(0),
        Err(poisoned) => poisoned
            .into_inner()
            .as_ref()
            .map(|run| run.port)
            .unwrap_or(0),
    }
}

/// Raises the listener. Idempotent: a second call keeps the port already held,
/// so a proxy restart does not leave the injected pages pointing at nothing.
pub fn start(preferred_port: u16) -> Option<u16> {
    let mut slot = match RUN.lock() {
        Ok(slot) => slot,
        Err(poisoned) => poisoned.into_inner(),
    };
    if let Some(run) = slot.as_ref() {
        return Some(run.port);
    }
    let mut listener = None;
    for port in [preferred_port, preferred_port + 1, preferred_port + 2, 0] {
        match TcpListener::bind((Ipv4Addr::LOCALHOST, port)) {
            Ok(bound) => {
                listener = Some(bound);
                break;
            }
            Err(_) => continue,
        }
    }
    let listener = listener?;
    let port = listener.local_addr().ok()?.port();
    let stop = Arc::new(AtomicBool::new(false));
    let thread_stop = Arc::clone(&stop);
    let thread = std::thread::Builder::new()
        .name("adfree-store-api".into())
        .spawn(move || accept_loop(listener, thread_stop));
    let thread = match thread {
        Ok(thread) => thread,
        Err(_) => return None,
    };
    crate::logbuf::push(&format!(
        "store: the userscript button answers on 127.0.0.1:{port}"
    ));
    *slot = Some(Running {
        port,
        stop,
        thread: Some(thread),
    });
    Some(port)
}

pub fn stop() {
    let running = match RUN.lock() {
        Ok(mut slot) => slot.take(),
        Err(poisoned) => poisoned.into_inner().take(),
    };
    let Some(mut running) = running else { return };
    running.stop.store(true, Ordering::SeqCst);
    // The accept loop wakes on the closed listener; connecting to it is the
    // cheapest way to make that happen from here.
    let _ = TcpStream::connect((Ipv4Addr::LOCALHOST, running.port));
    if let Some(thread) = running.thread.take() {
        let _ = thread.join();
    }
}

fn accept_loop(listener: TcpListener, stop: Arc<AtomicBool>) {
    let mut live = 0usize;
    while !stop.load(Ordering::Relaxed) {
        let Ok((stream, peer)) = listener.accept() else {
            if stop.load(Ordering::Relaxed) {
                return;
            }
            continue;
        };
        if !peer.ip().is_loopback() {
            continue;
        }
        if live >= MAX_CONNECTIONS {
            continue;
        }
        live += 1;
        let stop = Arc::clone(&stop);
        let spawned = std::thread::Builder::new()
            .name("adfree-store-req".into())
            .spawn(move || {
                serve(stream, &stop);
            });
        if spawned.is_err() {
            live -= 1;
            continue;
        }
        // The count is only a guard against a flood of half-open sockets; a
        // proper accounting would need the thread's own Drop, and a leaked
        // slot here costs nothing but some slack.
        live = live.saturating_sub(1);
    }
}

/// One request: headers, an optional JSON body, and an answer.
fn serve(mut stream: TcpStream, stop: &AtomicBool) {
    let _ = stream.set_read_timeout(Some(std::time::Duration::from_secs(5)));
    let _ = stream.set_write_timeout(Some(std::time::Duration::from_secs(5)));
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 1024];
    let head_end = loop {
        if stop.load(Ordering::Relaxed) {
            return;
        }
        match stream.read(&mut chunk) {
            Ok(0) => return,
            Ok(n) => buffer.extend_from_slice(&chunk[..n]),
            Err(_) => return,
        }
        if let Some(pos) = find_head_end(&buffer) {
            break pos;
        }
        if buffer.len() > MAX_REQUEST {
            return;
        }
    };
    let head = String::from_utf8_lossy(&buffer[..head_end]).into_owned();
    let mut lines = head.split("\r\n");
    let request_line = lines.next().unwrap_or_default();
    let mut method = "";
    let mut target = "";
    let mut parts = request_line.split_whitespace();
    if let (Some(m), Some(t)) = (parts.next(), parts.next()) {
        method = m;
        target = t;
    }
    let origin = lines.clone().find_map(|line| {
        let (name, value) = line.split_once(':')?;
        if name.eq_ignore_ascii_case("origin") {
            Some(value.trim().to_string())
        } else {
            None
        }
    });
    // The header has to echo the origin exactly — scheme included — while the
    // allowlist is decided on the host it names.
    let allowed_origin = origin
        .as_deref()
        .filter(|origin| origin_host(origin).is_some_and(is_store_host));
    // A preflight is the browser asking whether the page may talk to us at all.
    // The answer names the store origin it asked from, never `*`: another page
    // reading this port learns nothing it can use.
    if method == "OPTIONS" {
        let mut extra = Vec::new();
        if head.to_ascii_lowercase().contains("access-control-request-private-network") {
            extra.push("Access-Control-Allow-Private-Network: true");
        }
        write_response(
            &mut stream,
            "204 No Content",
            &extra,
            "application/json",
            "",
            allowed_origin,
        );
        return;
    }
    let Some(allowed_origin) = allowed_origin else {
        write_json(&mut stream, "403 Forbidden", "{\"ok\":false,\"error\":\"origin\"}", None);
        return;
    };
    // The body: whatever follows the header block. Everything that matters is
    // small JSON, and a longer body is a request this endpoint does not serve.
    let body = if buffer.len() > head_end {
        String::from_utf8_lossy(&buffer[head_end..]).into_owned()
    } else {
        String::new()
    };
    let body = if body.is_empty() { read_body(&mut stream) } else { body };
    let answer = route(method, target, &body);
    match answer {
        Answer::Json(payload) => write_json(&mut stream, "200 OK", &payload, Some(allowed_origin)),
        Answer::Status(code, payload) => {
            write_json(&mut stream, code, &payload, Some(allowed_origin))
        }
    }
}

fn read_body(stream: &mut TcpStream) -> String {
    let mut body = String::new();
    let _ = stream.read_to_string(&mut body);
    body.chars().take(MAX_REQUEST).collect()
}

fn find_head_end(bytes: &[u8]) -> Option<usize> {
    bytes
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .map(|pos| pos + 4)
}

/// The host an `Origin` header names, without the scheme or port.
fn origin_host(origin: &str) -> Option<&str> {
    let rest = origin.split_once("://").map(|(_, rest)| rest)?;
    let host = rest.split(['/', ':']).next()?;
    Some(host)
}

enum Answer {
    Json(String),
    Status(&'static str, String),
}

fn route(method: &str, target: &str, body: &str) -> Answer {
    let (path, query) = match target.split_once('?') {
        Some((path, query)) => (path, query),
        None => (target, ""),
    };
    let request: serde_json::Value = serde_json::from_str(body).unwrap_or_default();
    let presented = request
        .get("token")
        .and_then(|t| t.as_str())
        .or_else(|| query_param(query, "token"))
        .unwrap_or_default();
    if presented != token() {
        return Answer::Status("403 Forbidden", envelope(false, None, "token"));
    }
    if method != "POST" {
        return Answer::Status("405 Method Not Allowed", envelope(false, None, "method"));
    }
    match path {
        "/userscript/install" | "/userscript/update" => {
            let url = request
                .get("url")
                .and_then(|u| u.as_str())
                .unwrap_or_default()
                .trim()
                .to_string();
            match install(&url) {
                Ok(record) => Answer::Json(format!(
                    "{{\"ok\":true,\"id\":{},\"name\":{},\"version\":{}}}",
                    quote(&record.id),
                    quote(&record.name),
                    quote(&meta_version_of(&record.id)),
                )),
                Err(reason) => Answer::Json(envelope(false, None, &reason)),
            }
        }
        "/userscript/uninstall" => {
            let url = request
                .get("url")
                .and_then(|u| u.as_str())
                .unwrap_or_default()
                .trim()
                .to_string();
            match uninstall(&url) {
                Ok(name) => Answer::Json(format!("{{\"ok\":true,\"name\":{}}}", quote(&name))),
                Err(reason) => Answer::Json(envelope(false, None, &reason)),
            }
        }
        "/userscript/list" => Answer::Json(format!("{{\"ok\":true,\"scripts\":{}}}", installed_json())),
        _ => Answer::Status("404 Not Found", envelope(false, None, "route")),
    }
}

fn envelope(ok: bool, name: Option<&str>, error: &str) -> String {
    match (ok, name) {
        (true, Some(name)) => format!("{{\"ok\":true,\"name\":{}}}", quote(name)),
        (true, None) => "{\"ok\":true}".to_string(),
        (false, _) => format!("{{\"ok\":false,\"error\":{}}}", quote(error)),
    }
}

fn write_json(stream: &mut TcpStream, status: &str, body: &str, origin: Option<&str>) {
    write_response(stream, status, &[], "application/json; charset=utf-8", body, origin);
}

fn write_response(
    stream: &mut TcpStream,
    status: &str,
    extra: &[&str],
    content_type: &str,
    body: &str,
    origin: Option<&str>,
) {
    let mut head = format!(
        "HTTP/1.1 {status}\r\n\
         Content-Type: {content_type}\r\n\
         Content-Length: {}\r\n\
         Cache-Control: no-store\r\n\
         Vary: Origin\r\n",
        body.len()
    );
    if let Some(origin) = origin {
        head.push_str(&format!("Access-Control-Allow-Origin: {origin}\r\n"));
        head.push_str("Access-Control-Allow-Methods: POST, OPTIONS\r\n");
        head.push_str("Access-Control-Allow-Headers: content-type\r\n");
    }
    for line in extra {
        head.push_str(line);
        head.push_str("\r\n");
    }
    head.push_str("Connection: close\r\n\r\n");
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(body.as_bytes());
    let _ = stream.flush();
}

fn query_param<'a>(query: &'a str, name: &str) -> Option<&'a str> {
    query.split('&').find_map(|pair| {
        let (key, value) = pair.split_once('=')?;
        (key == name).then_some(value)
    })
}

/// How many times the store has changed the script list this run.
pub fn revision() -> u64 {
    REVISION.load(Ordering::Relaxed)
}

fn quote(value: &str) -> String {
    serde_json::Value::String(value.to_string()).to_string()
}

/// A stable key for one script across its install URLs.
///
/// Greasy Fork hands out `https://update.greasyfork.org/scripts/<id>/<slug>.user.js`,
/// and the slug carries a version-independent name, but the id is the only part
/// that never changes: an update rewrites the URL's tail when the script is
/// renamed. OpenUserJS and ScriptCat keep their own paths, so the key falls back
/// to the URL itself, minus the query.
fn key_for(url: &str) -> String {
    if let Some(rest) = url.split("update.greasyfork.org/scripts/").nth(1) {
        let id: String = rest.chars().take_while(char::is_ascii_digit).collect();
        if !id.is_empty() {
            return format!("greasyfork:{id}");
        }
    }
    if let Some(rest) = url.split("/scripts/").nth(1) {
        let id: String = rest.chars().take_while(char::is_ascii_digit).collect();
        if !id.is_empty() && url.contains("greasyfork.org") {
            return format!("greasyfork:{id}");
        }
    }
    url.split(['?', '#']).next().unwrap_or(url).to_string()
}

fn script_id_for(key: &str) -> String {
    format!("store-{:016x}", crate::fxhash::hash_str(key))
}

/// The userscripts the app has, as the page needs them: enough to tell whether
/// the one on screen is already installed, and under what name.
pub fn installed_json() -> String {
    let records = installed();
    let rows: Vec<String> = records
        .iter()
        .map(|record| {
            format!(
                "{{\"id\":{},\"name\":{},\"url\":{},\"key\":{},\"version\":{}}}",
                quote(&record.id),
                quote(&record.name),
                quote(&record.url),
                quote(&key_for(&record.url)),
                quote(&meta_version_of(&record.id))
            )
        })
        .collect();
    format!("[{}]", rows.join(","))
}

fn installed() -> Vec<ScriptRecord> {
    crate::core()
        .state()
        .map(|state| state.snapshot().scripts.scripts)
        .unwrap_or_default()
}

/// Everything the injected page needs, as JSON.
///
/// The token rides along because the page has nowhere else to get it. It is
/// only ever written into responses for [`is_store_host`] hosts, so no other
/// origin can read it, and it is worthless without the socket it names.
pub fn page_payload(host: &str) -> String {
    format!(
        "{{\"api\":{},\"host\":{},\"token\":{},\"installed\":{}}}",
        quote(&format!("http://127.0.0.1:{}/userscript", port())),
        quote(host),
        quote(token()),
        installed_json()
    )
}

/// Installs or updates one script from its install URL.
fn install(url: &str) -> Result<ScriptRecord, String> {
    if url.is_empty() {
        return Err("url".to_string());
    }
    if !url.starts_with("https://") && !url.starts_with("http://") {
        return Err("scheme".to_string());
    }
    let core = crate::core();
    let store = core.store().ok_or_else(|| "store".to_string())?;
    let body = crate::assets::fetch_text(url, MAX_SCRIPT_BYTES, std::time::Duration::from_secs(60))?;
    if !looks_like_userscript(&body) {
        return Err("not a userscript".to_string());
    }
    let key = key_for(url);
    let id = script_id_for(&key);
    crate::assets::save(&store.scripts_dir(), &id, "js", &body)?;
    let name = meta_value(&body, "name").unwrap_or_else(|| "Userscript".to_string());
    let description = meta_value(&body, "description").unwrap_or_default();
    let state = core.state().ok_or_else(|| "state".to_string())?;
    let mut scripts = state.snapshot().scripts;
    let existing = scripts.scripts.iter().position(|row| row.id == id);
    let record = ScriptRecord {
        id: id.clone(),
        name: name.clone(),
        description,
        url: url.to_string(),
        enabled: existing
            .and_then(|index| scripts.scripts.get(index))
            .map(|row| row.enabled)
            .unwrap_or(true),
    };
    match existing {
        Some(index) => scripts.scripts[index] = record.clone(),
        None => scripts.scripts.push(record.clone()),
    }
    scripts.master = true;
    if !state.save_scripts(scripts) {
        return Err("save".to_string());
    }
    // The engine reads the script list from the config it was handed, so the
    // install is only live once the plan is rebuilt — the same call the UI
    // makes after it saves.
    let _ = crate::state::config::apply(state, core, false);
    REVISION.fetch_add(1, Ordering::Relaxed);
    crate::logbuf::push(&format!("store: installed {name} from {url}"));
    Ok(record)
}

/// Removes one script: the row, the state, and the file it downloaded.
fn uninstall(url: &str) -> Result<String, String> {
    let key = key_for(url);
    let core = crate::core();
    let state = core.state().ok_or_else(|| "state".to_string())?;
    let mut scripts = state.snapshot().scripts;
    let Some(index) = scripts
        .scripts
        .iter()
        .position(|row| key_for(&row.url) == key)
    else {
        return Err("not installed".to_string());
    };
    let record = scripts.scripts.remove(index);
    if let Some(store) = core.store() {
        crate::assets::remove(&store.scripts_dir(), &record.id, "js");
    }
    if !state.save_scripts(scripts) {
        return Err("save".to_string());
    }
    let _ = crate::state::config::apply(state, core, false);
    REVISION.fetch_add(1, Ordering::Relaxed);
    crate::logbuf::push(&format!("store: removed {}", record.name));
    Ok(record.name)
}

/// A body that starts with the metadata block. The check is deliberately loose
/// and deliberately present: this is the one place the app writes a file that
/// a browser will execute, and a downloaded HTML error page must not become a
/// script.
fn looks_like_userscript(body: &str) -> bool {
    let head: String = body.lines().take(40).collect::<Vec<_>>().join("\n");
    head.contains("==UserScript==") && meta_value(&head, "name").is_some()
}

fn meta_value(body: &str, key: &str) -> Option<String> {
    for line in body.lines().take(60) {
        let line = line.trim_start_matches('/').trim();
        let line = line.trim_start_matches('*').trim();
        if let Some(rest) = line.strip_prefix('@') {
            let mut parts = rest.splitn(2, char::is_whitespace);
            let name = parts.next().unwrap_or_default();
            if name == key {
                let value = parts.next().unwrap_or_default().trim();
                if !value.is_empty() {
                    return Some(value.to_string());
                }
            }
        }
    }
    None
}

fn meta_version_of(id: &str) -> String {
    crate::core()
        .store()
        .and_then(|store| crate::assets::read_text(&store.scripts_dir(), id, "js"))
        .and_then(|body| meta_value(&body, "version"))
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The slug in a Greasy Fork install URL follows the script's name, and a
    /// rename rewrites it. The id is what stays, so it is the key: an update
    /// offered after a rename has to land on the script already installed.
    #[test]
    fn a_greasy_fork_key_survives_a_rename() {
        let before =
            "https://update.greasyfork.org/scripts/550659/Find%20Scripts.user.js";
        let after =
            "https://update.greasyfork.org/scripts/550659/Find%20Scripts%20For%20This%20Site.user.js";
        assert_eq!(key_for(before), "greasyfork:550659");
        assert_eq!(key_for(before), key_for(after));
        assert_ne!(key_for(before), key_for("https://update.greasyfork.org/scripts/1/x.user.js"));
    }

    /// Any other host falls back to its URL: there is no shared id scheme.
    #[test]
    fn another_store_keys_on_its_url() {
        assert_eq!(
            key_for("https://openuserjs.org/scripts/me/Thing.user.js?x=1"),
            "https://openuserjs.org/scripts/me/Thing.user.js"
        );
    }

    #[test]
    fn only_store_hosts_get_the_button() {
        assert!(is_store_host("greasyfork.org"));
        assert!(is_store_host("www.greasyfork.org"));
        assert!(is_store_host("sleazyfork.org"));
        assert!(!is_store_host("greasyfork.org.evil.example"));
        assert!(!is_store_host("evil.example"));
        assert!(!is_store_host("notgreasyfork.org"));
    }

    /// The one place the app writes a file a browser will execute: an HTML
    /// error page must not become one.
    #[test]
    fn a_body_without_a_metadata_block_is_not_a_script() {
        assert!(!looks_like_userscript("<html><body>404</body></html>"));
        assert!(looks_like_userscript(
            "// ==UserScript==\n// @name Thing\n// @version 1.2\n// ==/UserScript==\n"
        ));
        // A metadata block with no name is a fragment, not an installable script.
        assert!(!looks_like_userscript("// ==UserScript==\n// @version 1\n// ==/UserScript=="));
    }

    #[test]
    fn the_metadata_values_are_read_the_way_managers_read_them() {
        let body = "// ==UserScript==\n// @name         A Thing\n// @version      2.0.1\n// @description  does things\n// ==/UserScript==";
        assert_eq!(meta_value(body, "name").as_deref(), Some("A Thing"));
        assert_eq!(meta_value(body, "version").as_deref(), Some("2.0.1"));
        assert_eq!(meta_value(body, "description").as_deref(), Some("does things"));
        assert_eq!(meta_value(body, "namespace"), None);
    }

    /// An origin is echoed whole — the browser rejects a prefix — while the
    /// allowlist is decided on the host it names.
    #[test]
    fn an_origin_is_read_by_its_host() {
        assert_eq!(origin_host("https://greasyfork.org"), Some("greasyfork.org"));
        assert_eq!(origin_host("http://www.greasyfork.org:8080"), Some("www.greasyfork.org"));
        assert_eq!(origin_host("null"), None);
    }

    /// Nothing answers without the token, and the token is per run.
    #[test]
    fn a_request_without_the_token_is_refused() {
        let answer = route("POST", "/userscript/install", "{\"url\":\"https://x/y.user.js\"}");
        assert!(matches!(answer, Answer::Status("403 Forbidden", _)));
        let answer = route("POST", "/userscript/list", "{}");
        assert!(matches!(answer, Answer::Status("403 Forbidden", _)));
    }

    /// A POST anywhere but the three routes is a 404, with the token present.
    #[test]
    fn an_unknown_route_is_not_served() {
        let body = format!("{{\"token\":\"{}\"}}", token());
        let answer = route("POST", "/userscript/whatever", &body);
        assert!(matches!(answer, Answer::Status("404 Not Found", _)));
        let answer = route("GET", "/userscript/list", &body);
        assert!(matches!(answer, Answer::Status("405 Method Not Allowed", _)));
    }
}
