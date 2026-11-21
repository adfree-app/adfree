//! Cloudflare WARP as an upstream this proxy can hand flows to.
//!
//! WARP's client already owns the tunnel. Its `proxy` mode is a SOCKS5 server
//! on loopback — the tunnel is established by the client, and everything that
//! connects to that port leaves through Cloudflare without the caller knowing
//! WireGuard exists. That is the same shape as every other upstream this tree
//! speaks (`socks5.rs`, `httpconnect.rs`), so the engine needs nothing new: the
//! integration is knowing whether the client is there, whether it is up, and on
//! which port.
//!
//! Why not WireGuard in-process. There are Rust crates for the pieces —
//! `boringtun` (Cloudflare's own, BSD-3) for the protocol and `warp-wireguard-gen`
//! (GPL-3.0) for registration — but the whole is a different project: between
//! the protocol and a TCP stream this proxy can forward sits a userspace
//! TCP/IP stack, and WARP's current default transport is MASQUE (HTTP/3), not
//! WireGuard, so the tunnel half would cover only the older path. It also adds
//! a licence this tree cannot take: it ships Apache-2.0, and a GPL-3.0
//! dependency would carry the whole app with it.
//!
//! Nothing here runs unless something asks: the row that shows WARP's state is
//! the only caller, and it is a `warp-cli` process, not a per-packet cost.

use std::process::Command;

/// Where the client listens in proxy mode when nothing has been configured.
pub const DEFAULT_PROXY_PORT: u16 = 40000;

/// What this machine says about its Cloudflare WARP client.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Warp {
    /// `warp-cli` is on this machine at all.
    pub installed: bool,
    /// The client's own tunnel is up.
    pub connected: bool,
    /// `warp`, `proxy`, `doh`, `dot`, `tunnel_only` — only `proxy` listens.
    pub mode: String,
    /// The port a SOCKS5 client would connect to.
    pub proxy_port: u16,
    /// The line the client printed, for the row that shows it verbatim.
    pub detail: String,
}

impl Warp {
    /// Whether this is an upstream a flow could actually be handed to.
    ///
    /// A connected client in `warp` mode is a tunnel for *this machine's* own
    /// traffic and offers no socket: claiming it as an upstream would report
    /// flows going through WARP while they went direct.
    pub fn usable(&self) -> bool {
        self.installed && self.connected && self.mode.eq_ignore_ascii_case("proxy")
    }
}

/// Asks the client what it is doing. `warp-cli` talks to the daemon over a
/// socket, so this needs no privilege of its own.
pub fn state() -> Warp {
    let status = match run(&["--accept-tos", "status"]) {
        Some(text) => text,
        // A missing client and a client that will not answer are the same
        // answer to the only question that matters: nothing to hand flows to.
        None => return Warp::default(),
    };
    let settings = run(&["--accept-tos", "settings"]).unwrap_or_default();
    let (connected, detail) = parse_status(&status);
    Warp {
        installed: true,
        connected,
        mode: parse_mode(&settings).unwrap_or_else(|| "warp".to_string()),
        proxy_port: parse_proxy_port(&settings).unwrap_or(DEFAULT_PROXY_PORT),
        detail,
    }
}

fn run(args: &[&str]) -> Option<String> {
    let output = Command::new("warp-cli").args(args).output().ok()?;
    if !output.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// `Status update: Connected` / `Status update: Disconnected`, with the reason
/// line that follows it when the client gives one.
///
/// A `Connecting` or `Reconnecting` line is not connected: a tunnel in the
/// middle of a handshake has no socket to forward to yet, and a row that says
/// otherwise sends every flow into a port that is not listening.
pub fn parse_status(text: &str) -> (bool, String) {
    let mut connected = false;
    let mut reason = String::new();
    for line in text.lines() {
        let line = line.trim();
        if let Some(value) = line.split_once("Status update:").map(|(_, v)| v.trim()) {
            connected = value.eq_ignore_ascii_case("connected");
        } else if let Some(value) = line.split_once("Reason:").map(|(_, v)| v.trim()) {
            reason = value.to_string();
        }
    }
    let detail = if reason.is_empty() {
        String::new()
    } else {
        reason
    };
    (connected, detail)
}

/// The operating mode, from `settings`' `Mode: Warp` line. The prefix the client
/// puts in front of it (`(default)`, `(override)`, `(network policy)`) is not
/// part of the answer.
pub fn parse_mode(text: &str) -> Option<String> {
    for line in text.lines() {
        let value = line.split_once("Mode:").map(|(_, v)| v.trim())?;
        // `Post-quantum support for MASQUE` and `Exclude mode, with hosts/ips:`
        // both contain the word; only this one is followed by a bare word.
        if value.is_empty() || value.contains(' ') || value.contains(',') {
            continue;
        }
        return Some(value.to_string());
    }
    None
}

/// The port proxy mode listens on, when the client reports one.
pub fn parse_proxy_port(text: &str) -> Option<u16> {
    for line in text.lines() {
        let trimmed = line.trim();
        let value = trimmed
            .split_once("Proxy Port:")
            .or_else(|| trimmed.split_once("Proxy port:"))
            .map(|(_, v)| v.trim())?;
        if let Ok(port) = value.parse::<u16>() {
            return Some(port);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The exact shape the client prints on a machine with no tunnel.
    const DISCONNECTED: &str = "Status update: Disconnected\nReason: Manual Disconnection\n";

    #[test]
    fn a_disconnected_client_says_why() {
        assert_eq!(
            parse_status(DISCONNECTED),
            (false, "Manual Disconnection".to_string())
        );
    }

    /// A handshake in flight is not a tunnel: forwarding to a port that is not
    /// listening yet is a machine with no web.
    #[test]
    fn connecting_is_not_connected() {
        assert!(
            !parse_status("Status update: Connecting\n").0,
            "a client mid-handshake was taken for an upstream"
        );
        assert!(parse_status("Status update: Connected\n").0);
    }

    /// The settings screen carries the word `mode` twice, and only one of them
    /// names the mode.
    #[test]
    fn the_mode_is_the_bare_word_after_the_label() {
        let settings = "(default)\tMode: Warp\n\
                        (network policy)\tWARP tunnel protocol: MASQUE\n\
                        (api defaults)\tExclude mode, with hosts/ips:\n";
        assert_eq!(parse_mode(settings).as_deref(), Some("Warp"));
        assert_eq!(
            parse_mode("(override)\tMode: proxy\n").as_deref(),
            Some("proxy")
        );
        assert_eq!(parse_mode("nothing here\n"), None);
    }

    #[test]
    fn the_proxy_port_is_read_when_the_client_states_one() {
        assert_eq!(
            parse_proxy_port("(default)\tProxy Port: 41234\n"),
            Some(41234)
        );
        assert_eq!(parse_proxy_port("(default)\tMode: Warp\n"), None);
        // Junk where a port belongs is not a port.
        assert_eq!(parse_proxy_port("Proxy Port: soon\n"), None);
    }

    /// Only a connected `proxy` client is an upstream. A connected `warp`
    /// client is a tunnel for this machine's own traffic and holds no socket.
    #[test]
    fn only_a_connected_proxy_client_is_an_upstream() {
        let mut warp = Warp {
            installed: true,
            connected: true,
            mode: "proxy".to_string(),
            proxy_port: DEFAULT_PROXY_PORT,
            detail: String::new(),
        };
        assert!(warp.usable());
        warp.mode = "Warp".to_string();
        assert!(
            !warp.usable(),
            "a tunnel with no socket was taken as an upstream"
        );
        warp.mode = "proxy".to_string();
        warp.connected = false;
        assert!(!warp.usable());
        warp.connected = true;
        warp.installed = false;
        assert!(!warp.usable());
    }
}
