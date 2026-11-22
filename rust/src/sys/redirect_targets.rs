//! What a redirected connection asked for, kept where the proxy can find it.
//!
//! The Windows redirect re-points a client's packet at `127.0.0.1:proxy_port`
//! and rewrites its source to loopback, so by the time the proxy accepts it both
//! ends name the loopback hop and the origin is nowhere in the socket: Windows
//! has no `SO_ORIGINAL_DST` to ask. The client's **port** is the one thing the
//! rewrite leaves alone, and the outbound pass knows the destination it
//! replaced — so it writes the pair down here, and the accepted connection
//! looks itself up by the peer it arrived from.
//!
//! Kept apart from the sockets so that the table is arithmetic a test can hold,
//! rather than a thing only a Windows box with the driver loaded can reach.

use std::collections::HashMap;
use std::net::SocketAddr;

/// How long a remembered destination is worth anything.
///
/// A TCP flow that has sent nothing for two minutes is over, and the port it
/// came from belongs to somebody else by then: answering an old entry would
/// hand a new connection to an origin it never named.
pub const TTL_MS: u64 = 120_000;

/// The most pairs held at once. A browser opens dozens of flows a second; the
/// table is a sliding window, not an archive.
pub const CAP: usize = 8192;

/// One redirected flow, as the outbound pass saw it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Entry {
    target: SocketAddr,
    seen: u64,
}

/// Destinations by the client port they were redirected from.
#[derive(Debug, Default)]
pub struct Targets {
    map: HashMap<u16, Entry>,
}

impl Targets {
    /// Writes down what `client_port` was reaching for, at `now` (milliseconds).
    ///
    /// A port that comes back with a different destination is a new flow: the
    /// last thing the port said is the only thing it can mean.
    pub fn note(&mut self, client_port: u16, target: SocketAddr, now: u64) {
        if self.map.len() >= CAP && !self.map.contains_key(&client_port) {
            self.make_room(now);
        }
        self.map.insert(client_port, Entry { target, seen: now });
    }

    /// What `client_port` was reaching for, if it was still within [`TTL_MS`].
    pub fn lookup(&mut self, client_port: u16, now: u64) -> Option<SocketAddr> {
        let entry = *self.map.get(&client_port)?;
        if now.saturating_sub(entry.seen) >= TTL_MS {
            self.map.remove(&client_port);
            return None;
        }
        Some(entry.target)
    }

    /// How many pairs are held right now.
    pub fn len(&self) -> usize {
        self.map.len()
    }

    /// Whether nothing is held.
    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }

    /// Drops what is past its life, and — when that is not enough — the oldest
    /// pair, so that a port marked once and never asked about again cannot keep
    /// a slot forever.
    fn make_room(&mut self, now: u64) {
        self.map.retain(|_, e| now.saturating_sub(e.seen) < TTL_MS);
        if self.map.len() < CAP {
            return;
        }
        if let Some(oldest) = self
            .map
            .iter()
            .min_by_key(|(_, e)| e.seen)
            .map(|(port, _)| *port)
        {
            self.map.remove(&oldest);
        }
    }
}

/// The process's table, written by the redirect's outbound pass.
static TARGETS: std::sync::Mutex<Option<Targets>> = std::sync::Mutex::new(None);

/// Milliseconds since the epoch, the clock the entries are aged by.
fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// The table, made on first use.
///
/// A poisoned lock is not worth failing a connection over: the table is a hint,
/// and a hint that cannot be read falls back to the request head, which is
/// exactly what happens on a platform that never fills it.
fn with<R>(f: impl FnOnce(&mut Targets) -> R) -> Option<R> {
    let mut slot = TARGETS.lock().ok()?;
    let table = slot.get_or_insert_with(Targets::default);
    Some(f(table))
}

/// Records that `client_port`'s flow was redirected towards `target`.
pub fn note(client_port: u16, target: SocketAddr) {
    let now = now_ms();
    with(|table| table.note(client_port, target, now));
}

/// The destination a redirected flow at `client_port` asked for.
pub fn lookup(client_port: u16) -> Option<SocketAddr> {
    let now = now_ms();
    with(|table| table.lookup(client_port, now)).flatten()
}

/// Forgets everything. Called when protection stops, and by the tests.
pub fn clear() {
    with(|table| table.map.clear());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn addr(port: u16) -> SocketAddr {
        format!("93.184.216.{port}:443").parse().unwrap()
    }

    #[test]
    fn a_remembered_destination_is_the_one_the_connection_asked_for() {
        let mut table = Targets::default();
        table.note(50123, addr(34), 1_000);
        assert_eq!(table.lookup(50123, 1_200), Some(addr(34)));
        assert_eq!(
            table.lookup(50124, 1_200),
            None,
            "a port nobody redirected names nothing"
        );
    }

    #[test]
    fn a_reused_port_speaks_for_the_flow_that_has_it_now() {
        let mut table = Targets::default();
        table.note(50123, addr(34), 1_000);
        table.note(50123, addr(20), 2_000);
        assert_eq!(
            table.lookup(50123, 2_100),
            Some(addr(20)),
            "the newest destination is the one a live port means"
        );
    }

    #[test]
    fn a_destination_is_forgotten_once_its_minutes_are_up() {
        let mut table = Targets::default();
        table.note(50123, addr(34), 1_000);
        assert_eq!(table.lookup(50123, 1_000 + TTL_MS - 1), Some(addr(34)));
        assert_eq!(table.lookup(50123, 1_000 + TTL_MS), None);
        assert_eq!(table.len(), 0, "a stale pair is not kept for later");
    }

    #[test]
    fn a_full_table_makes_room_instead_of_growing() {
        let mut table = Targets::default();
        for port in 0..CAP as u16 {
            table.note(port, addr(1), 1_000);
        }
        assert_eq!(table.len(), CAP);
        // Everything above is old by the time this one arrives, and a port the
        // table never held has to be able to get in.
        table.note(40_000, addr(2), 1_000 + TTL_MS);
        assert!(table.len() <= CAP, "the table stopped at its cap");
        assert_eq!(table.lookup(40_000, 1_000 + TTL_MS), Some(addr(2)));
        assert_eq!(
            table.lookup(0, 1_000 + TTL_MS),
            None,
            "what went stale went out"
        );
    }

    #[test]
    fn the_process_table_can_be_written_and_read() {
        clear();
        note(51234, addr(7));
        assert_eq!(lookup(51234), Some(addr(7)));
        clear();
        assert_eq!(lookup(51234), None, "clearing takes the pairs with it");
    }
}
