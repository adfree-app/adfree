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
//! amount of memory instead of growing. Each side also keeps a [`MAX_BODY`]
//! preview of what followed its head. It is the wire, not the document: still
//! compressed, still chunked, and short by design — undoing the transfer and
//! content encodings per response is work that belongs behind its own switch,
//! not in the middle of a stream.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

/// Longest head kept, per side. A head past this is a URL with a very long
/// query or a cookie dump, and the first four kilobytes are the part that
/// identifies it.
pub const MAX_HEAD: usize = 4096;

/// Longest body preview kept. This is the wire bytes as they arrived — still
/// compressed, still chunked, exactly what a packet capture would show — so it
/// is short by design: the heads answer "what", the preview answers "does it
/// look like what it claims".
pub const MAX_BODY: usize = 2048;

/// Records kept before the oldest is dropped.
pub const CAPACITY: usize = 300;

/// Frames kept per socket. A chat or a multiplayer game speaks for as long as
/// it is open, and a ring of its own is what keeps one socket from filling the
/// tracker: the oldest frame goes before the row it belongs to.
pub const MAX_FRAMES: usize = 200;

/// One WebSocket frame that crossed an upgraded connection.
#[derive(Clone, Debug, Default)]
pub struct Frame {
    pub ts_ms: u64,
    /// Sent by the client rather than received.
    pub outgoing: bool,
    /// RFC 6455 opcode: 0 continuation, 1 text, 2 binary, 8 close, 9 ping, 10 pong.
    pub opcode: u8,
    /// The payload length the frame declared, preview or not.
    pub len: usize,
    /// The first [`crate::ws::MAX_PAYLOAD`] bytes, unmasked.
    pub preview: String,
}

/// One request and its answer, as they crossed the connection.
#[derive(Clone, Debug, Default)]
pub struct Record {
    /// Milliseconds since the Unix epoch, the clock the log uses.
    pub ts_ms: u64,
    /// Monotonic id, so a UI can order records it received in two batches.
    pub id: u64,
    pub url: String,
    /// The method from the request line, so the list can sort and colour by it
    /// without re-reading the head on every frame.
    pub method: String,
    /// The status the origin answered with; 0 while the answer is still out.
    pub status: u16,
    /// The request head, verbatim, up to [`MAX_HEAD`] bytes.
    pub request_head: String,
    /// The first [`MAX_BODY`] wire bytes of the request's body, lossily
    /// decoded, so a form or a JSON post can be read without a second capture.
    pub request_body_preview: String,
    /// How many body bytes the request carried, preview or not.
    pub request_body_len: usize,
    /// The response head, verbatim, up to [`MAX_HEAD`] bytes.
    pub response_head: String,
    /// The first [`MAX_BODY`] wire bytes of the body, lossily decoded: this is
    /// the transfer, not the usable document, so a gzip body starts with its
    /// own magic and a chunked one with its sizes.
    pub body_preview: String,
    /// How many body bytes the connection carried in that read, which can be
    /// far more than the preview kept.
    pub body_len: usize,
    /// The frames this connection carried after a protocol switch, the newest
    /// last, up to [`MAX_FRAMES`].
    pub frames: Vec<Frame>,
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

/// The method a request head opens with. A head that names none is a `GET`,
/// which is what a client that left it out would be understood to mean.
pub fn method_of(head: &[u8]) -> String {
    let line_end = head.iter().position(|b| *b == b'\n').unwrap_or(head.len());
    let line = String::from_utf8_lossy(&head[..line_end]);
    let method = line.split(' ').next().unwrap_or("").trim().to_string();
    if method.is_empty() {
        "GET".to_string()
    } else {
        method
    }
}

/// The byte just past the blank line that ends a head, where the body starts.
/// `0` for a head that is still being read, and the whole slice for one that
/// never ends — a request body cannot be told from a head without it.
fn body_start(bytes: &[u8]) -> usize {
    let mut i = 0;
    while i + 3 < bytes.len() {
        if &bytes[i..i + 4] == b"\r\n\r\n" {
            return i + 4;
        }
        i += 1;
    }
    0
}

/// The URL a request head names, for the ring's left column.
///
/// The head carries a target, not an origin: `GET /a?b=c HTTP/1.1`. The host
/// comes from the session, which is the name the client asked for and the one
/// the certificate was minted for — the `Host` header can disagree with it, and
/// when it does, the session name is the connection that actually happened.
///
/// An absolute-form target (a proxy-style `GET http://host/path`) is already a
/// URL and is left alone.
pub fn url_for(host: &str, head: &[u8]) -> String {
    let line_end = head.iter().position(|b| *b == b'\n').unwrap_or(head.len());
    let line = String::from_utf8_lossy(&head[..line_end]);
    let mut parts = line.split(' ');
    let _method = parts.next().unwrap_or("");
    let target = parts.next().unwrap_or("/");
    if target.starts_with("http://") || target.starts_with("https://") {
        return target.to_string();
    }
    if host.is_empty() {
        return target.to_string();
    }
    format!("https://{host}{target}")
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
    // The client's buffer holds head and body in one piece. Cutting at the
    // blank line keeps a form or a JSON post where it can be read, instead of
    // letting it run past the head's four kilobytes and lose its beginning.
    let split = body_start(head);
    // No blank line means the whole buffer is still the head: a request that
    // has not finished arriving has no body to tell apart yet.
    let (request_head, request_body) = if split == 0 {
        (head, &head[head.len()..])
    } else {
        head.split_at(split)
    };
    let record = Record {
        ts_ms: now_ms(),
        id,
        url: url.to_string(),
        method: method_of(head),
        status: 0,
        request_head: clip(request_head),
        request_body_preview: clip_body(request_body),
        request_body_len: request_body.len(),
        response_head: String::new(),
        body_preview: String::new(),
        body_len: 0,
        frames: Vec::new(),
    };
    let mut ring = ring().lock().ok()?;
    if ring.len() == CAPACITY {
        ring.pop_front();
    }
    ring.push_back(record);
    Some(id)
}

/// Completes one record with the answer's head and the body that followed it.
///
/// `body` is the wire payload of that read: the caller has it in hand already,
/// so this costs a copy of at most [`MAX_BODY`] bytes and a length.
pub fn record_response(id: u64, status: u16, head: &[u8], body: &[u8]) {
    if !enabled() {
        return;
    }
    let Ok(mut ring) = ring().lock() else {
        return;
    };
    if let Some(record) = ring.iter_mut().find(|record| record.id == id) {
        record.status = status;
        record.response_head = clip(head);
        record.body_len = body.len();
        record.body_preview = clip_body(body);
    }
}

/// Records one frame of an upgraded connection.
///
/// Frames are matched by the record's id rather than appended by position: a
/// connection that was upgraded is the one the row belongs to, and the ring may
/// have dropped that row already if it was old enough.
pub fn record_frame(id: u64, frame: &crate::ws::Frame, outgoing: bool) {
    if !enabled() {
        return;
    }
    let Ok(mut ring) = ring().lock() else {
        return;
    };
    let Some(record) = ring.iter_mut().find(|record| record.id == id) else {
        return;
    };
    if record.frames.len() == MAX_FRAMES {
        record.frames.remove(0);
    }
    record.frames.push(Frame {
        ts_ms: now_ms(),
        outgoing,
        opcode: frame.opcode,
        len: frame.len,
        preview: String::from_utf8_lossy(&frame.payload).into_owned(),
    });
}

/// A body is binary, so the preview is lossy and never longer than
/// [`MAX_BODY`].
fn clip_body(body: &[u8]) -> String {
    let cut = body.len().min(MAX_BODY);
    String::from_utf8_lossy(&body[..cut]).into_owned()
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
            record_response(
                id,
                200,
                b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n",
                b"<html>hi</html>",
            );

            let records = snapshot();
            assert_eq!(records.len(), 1);
            let record = &records[0];
            assert_eq!(record.url, "https://example.com/a");
            assert_eq!(record.status, 200);
            assert!(record.request_head.contains("Host: example.com"));
            assert!(record.response_head.contains("text/html"));
            assert_eq!(record.body_preview, "<html>hi</html>");
            assert_eq!(record.body_len, 15);
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
    fn the_method_and_the_body_are_kept_apart_from_the_head() {
        serial(|| {
            set_enabled(true);
            record_request(
                "https://example.com/post",
                b"POST /post HTTP/1.1\r\nHost: example.com\r\n\r\n{\"a\":1}",
            )
            .expect("the tracker is on");

            let records = snapshot();
            let record = &records[0];
            assert_eq!(record.method, "POST");
            assert!(record.request_head.ends_with("Host: example.com\r\n\r\n"));
            assert!(!record.request_head.contains("{\"a\":1}"));
            assert_eq!(record.request_body_preview, "{\"a\":1}");
            assert_eq!(record.request_body_len, 7);
        });
    }

    #[test]
    fn a_request_with_no_body_has_an_empty_one() {
        serial(|| {
            set_enabled(true);
            record_request(
                "https://example.com/",
                b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            )
            .expect("on");
            let records = snapshot();
            assert_eq!(records[0].method, "GET");
            assert_eq!(records[0].request_body_len, 0);
            assert!(records[0].request_body_preview.is_empty());
        });
    }

    #[test]
    fn a_head_without_a_blank_line_is_not_mistaken_for_a_body() {
        serial(|| {
            set_enabled(true);
            record_request(
                "https://example.com/",
                b"GET / HTTP/1.1\r\nHost: example.com",
            )
            .expect("on");
            let records = snapshot();
            assert_eq!(records[0].request_body_len, 0);
            assert!(records[0].request_head.contains("Host"));
        });
    }

    #[test]
    fn a_frame_is_appended_to_the_socket_it_arrived_on() {
        serial(|| {
            set_enabled(true);
            let id = record_request(
                "https://example.com/socket",
                b"GET /socket HTTP/1.1\r\n\r\n",
            )
            .expect("the tracker is on");
            record_response(id, 101, b"HTTP/1.1 101 Switching Protocols\r\n\r\n", &[]);
            record_frame(
                id,
                &crate::ws::Frame {
                    fin: true,
                    opcode: 1,
                    len: 5,
                    payload: b"hello".to_vec(),
                },
                true,
            );
            record_frame(
                id,
                &crate::ws::Frame {
                    fin: true,
                    opcode: 1,
                    len: 2,
                    payload: b"hi".to_vec(),
                },
                false,
            );

            let records = snapshot();
            let frames = &records[0].frames;
            assert_eq!(frames.len(), 2);
            assert!(frames[0].outgoing);
            assert_eq!(frames[0].preview, "hello");
            assert_eq!(frames[0].len, 5);
            assert!(!frames[1].outgoing);
            assert_eq!(frames[1].preview, "hi");
        });
    }

    #[test]
    fn a_talkative_socket_keeps_only_the_newest_frames() {
        serial(|| {
            set_enabled(true);
            let id = record_request(
                "https://example.com/socket",
                b"GET /socket HTTP/1.1\r\n\r\n",
            )
            .expect("on");
            for index in 0..MAX_FRAMES + 5 {
                record_frame(
                    id,
                    &crate::ws::Frame {
                        fin: true,
                        opcode: 1,
                        len: 1,
                        payload: vec![b'a' + (index % 26) as u8],
                    },
                    index % 2 == 0,
                );
            }
            let records = snapshot();
            assert_eq!(records[0].frames.len(), MAX_FRAMES);
            // The oldest five are gone; the newest one is still the newest.
            assert_eq!(records[0].frames[MAX_FRAMES - 1].len, 1);
            assert_eq!(
                records[0].frames[MAX_FRAMES - 1].preview,
                ((b'a' + ((MAX_FRAMES + 4) % 26) as u8) as char).to_string()
            );
        });
    }

    #[test]
    fn a_frame_for_a_dropped_row_is_dropped_too() {
        serial(|| {
            set_enabled(true);
            assert!(record_request("https://example.com/", b"GET / HTTP/1.1\r\n\r\n").is_some());
            // An id the ring never held: a socket outliving its row must not
            // resurrect it or panic.
            record_frame(
                999,
                &crate::ws::Frame {
                    fin: true,
                    opcode: 1,
                    len: 1,
                    payload: b"x".to_vec(),
                },
                false,
            );
            assert!(snapshot()[0].frames.is_empty());
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
    fn a_body_preview_is_cut_and_keeps_its_length() {
        serial(|| {
            set_enabled(true);
            let id = record_request("https://example.com/big", b"GET / HTTP/1.1\r\n").expect("on");
            let body = vec![0xffu8; MAX_BODY * 3];
            record_response(id, 200, b"HTTP/1.1 200 OK\r\n", &body);

            let records = snapshot();
            assert_eq!(records[0].body_len, MAX_BODY * 3);
            // Lossy decoding turns each kept byte into one replacement
            // character, which is three bytes of UTF-8: the cut is on bytes.
            assert_eq!(records[0].body_preview.chars().count(), MAX_BODY);
        });
    }

    #[test]
    fn the_url_is_the_connection_not_the_header() {
        assert_eq!(
            url_for(
                "example.com",
                b"GET /a?b=c HTTP/1.1\r\nHost: other.test\r\n"
            ),
            "https://example.com/a?b=c"
        );
        assert_eq!(
            url_for("example.com", b"POST /x HTTP/1.1\r\n"),
            "https://example.com/x"
        );
        // A proxy-style absolute target is already a URL.
        assert_eq!(
            url_for("example.com", b"GET http://plain.test/y HTTP/1.1\r\n"),
            "http://plain.test/y"
        );
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
