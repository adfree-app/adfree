//! A request tracker for one window, and nothing when the window is closed.
//!
//! The filtering log already answers "was this blocked, and by which rule",
//! which is a decision. This answers a different question — "what did the
//! connection actually carry" — and it is the only place in the tree where the
//! head of a request is kept in one piece beside the head of its answer.
//!
//! It costs the packet path one atomic load while it is off. While it is on it
//! copies at most [`MAX_HEAD`] bytes of each head into a ring that holds
//! [`CAPACITY`] records, so a window left open on a busy page stops at a fixed
//! amount of memory instead of growing. Bodies are not kept: reading them means
//! undoing the transfer encoding and the content encoding per response, and
//! that work belongs behind its own switch, not in the middle of a stream.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

/// Longest head kept, per side. A head past this is a URL with a very long
/// query or a cookie dump, and the first four kilobytes are the part that
/// identifies it.
pub const MAX_HEAD: usize = 4096;

/// Records kept before the oldest is dropped.
pub const CAPACITY: usize = 300;

/// One request and its answer, as they crossed the connection.
#[derive(Clone, Debug, Default)]
pub struct Record {
    /// Milliseconds since the Unix epoch, the clock the log uses.
    pub ts_ms: u64,
    /// Monotonic id, so a UI can order records it received in two batches.
    pub id: u64,
    pub url: String,
    /// The status the origin answered with; 0 while the answer is still out.
    pub status: u16,
    /// The request head, verbatim, up to [`MAX_HEAD`] bytes.
    pub request_head: String,
    /// The response head, verbatim, up to [`MAX_HEAD`] bytes.
    pub response_head: String,
}

static ENABLED: AtomicBool = AtomicBool::new(false);
static NEXT_ID: AtomicU64 = AtomicU64::new(1);
static RING: OnceLock<Mutex<VecDeque<Record>>> = OnceLock::new();

fn ring() -> &'static Mutex<VecDeque<Record>> {
    RING.get_or_init(|| Mutex::new(VecDeque::with_capacity(CAPACITY)))
}

/// Whether a request head is worth copying. Read once per request, on the
/// request path, which is why it is an atomic and not a lock.
pub fn enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

/// Turns the tracker on or off. Turning it off keeps what was recorded;
/// [`clear`] is the one that throws it away.
pub fn set_enabled(on: bool) {
    ENABLED.store(on, Ordering::Relaxed);
}

/// Empties the ring without touching the switch.
pub fn clear() {
    if let Ok(mut ring) = ring().lock() {
        ring.clear();
    }
}

/// How many records are held.
pub fn len() -> usize {
    ring().lock().map(|ring| ring.len()).unwrap_or(0)
}

/// Copies a head into the ring's own bytes, cut at [`MAX_HEAD`].
///
/// Lossy on purpose: a header name or a cookie value is not UTF-8 by contract,
/// and a tracker that skipped those requests would be missing the interesting
/// ones.
fn clip(head: &[u8]) -> String {
    let cut = head.len().min(MAX_HEAD);
    String::from_utf8_lossy(&head[..cut]).into_owned()
}

/// Records one request head. The answer arrives later and completes the
/// record by id.
///
/// Returns the id so the response side can find the row again; `None` when the
/// tracker is off, which is the common case and costs nothing but the branch
/// the caller already made.
pub fn record_request(url: &str, head: &[u8]) -> Option<u64> {
    if !enabled() {
        return None;
    }
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    let record = Record {
        ts_ms: now_ms(),
        id,
        url: url.to_string(),
        status: 0,
        request_head: clip(head),
        response_head: String::new(),
    };
    let mut ring = ring().lock().ok()?;
    if ring.len() == CAPACITY {
        ring.pop_front();
    }
    ring.push_back(record);
    Some(id)
}

/// Completes one record with the answer's head.
pub fn record_response(id: u64, status: u16, head: &[u8]) {
    if !enabled() {
        return;
    }
    let Ok(mut ring) = ring().lock() else {
        return;
    };
    if let Some(record) = ring.iter_mut().find(|record| record.id == id) {
        record.status = status;
        record.response_head = clip(head);
    }
}

/// The records, oldest first.
pub fn snapshot() -> Vec<Record> {
    ring()
        .lock()
        .map(|ring| ring.iter().cloned().collect())
        .unwrap_or_default()
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The ring and the switch are process-wide, so the tests that touch them
    /// run one at a time.
    fn serial(test: impl FnOnce()) {
        static LOCK: Mutex<()> = Mutex::new(());
        let _guard = LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        set_enabled(false);
        clear();
        test();
        set_enabled(false);
        clear();
    }

    #[test]
    fn nothing_is_recorded_while_the_tracker_is_off() {
        serial(|| {
            assert!(record_request("https://example.com/", b"GET / HTTP/1.1\r\n").is_none());
            assert_eq!(len(), 0);
        });
    }

    #[test]
    fn a_request_is_completed_by_its_answer() {
        serial(|| {
            set_enabled(true);
            let id = record_request(
                "https://example.com/a",
                b"GET /a HTTP/1.1\r\nHost: example.com\r\n",
            )
            .expect("the tracker is on");
            record_response(id, 200, b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n");

            let records = snapshot();
            assert_eq!(records.len(), 1);
            let record = &records[0];
            assert_eq!(record.url, "https://example.com/a");
            assert_eq!(record.status, 200);
            assert!(record.request_head.contains("Host: example.com"));
            assert!(record.response_head.contains("text/html"));
            assert!(record.ts_ms > 0);
        });
    }

    #[test]
    fn a_long_head_is_cut_and_not_dropped() {
        serial(|| {
            set_enabled(true);
            let head = vec![b'x'; MAX_HEAD * 2];
            let id = record_request("https://example.com/big", &head).expect("the tracker is on");

            let records = snapshot();
            assert_eq!(records.len(), 1);
            assert_eq!(records[0].request_head.len(), MAX_HEAD);
            assert_eq!(records[0].id, id);
        });
    }

    #[test]
    fn the_ring_stops_at_its_capacity() {
        serial(|| {
            set_enabled(true);
            for index in 0..(CAPACITY + 5) {
                record_request(
                    &format!("https://example.com/{index}"),
                    b"GET / HTTP/1.1\r\n",
                );
            }

            let records = snapshot();
            assert_eq!(records.len(), CAPACITY);
            // The oldest went first, so the window is the tail of the run.
            assert!(records
                .last()
                .unwrap()
                .url
                .ends_with(&format!("/{}", CAPACITY + 4)));
            assert!(!records.iter().any(|record| record.url.ends_with("/0")));
        });
    }

    #[test]
    fn turning_it_off_keeps_what_was_recorded() {
        serial(|| {
            set_enabled(true);
            record_request("https://example.com/kept", b"GET / HTTP/1.1\r\n");
            set_enabled(false);

            assert_eq!(len(), 1);
            assert!(record_request("https://example.com/later", b"GET / HTTP/1.1\r\n").is_none());
            assert_eq!(len(), 1);
        });
    }
}
