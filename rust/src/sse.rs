//! An event stream, read while it arrives.
//!
//! `text/event-stream` is the one response shape where waiting for the end is
//! the same as not watching: a page that streams for minutes would show
//! nothing until it stopped. This reads the framing incrementally — a line that
//! has not finished stays buffered, a blank line dispatches — so each event can
//! be handed on the moment it is whole.
//!
//! It reads and does not rewrite: the bytes are parsed from a copy of what the
//! proxy already has in hand, and the client gets them unchanged.

/// Longest partial line kept. A stream that never sends a newline would
/// otherwise grow this buffer for as long as it is open.
pub const MAX_LINE: usize = 8192;

/// Longest payload kept per event. The rest is counted, not stored: a reader
/// wants to see that an event came and roughly what it said.
pub const MAX_DATA: usize = 2048;

/// One complete event.
#[derive(Clone, Debug, Default)]
pub struct Event {
    /// The `event:` field, or `message` when the stream named none.
    pub name: String,
    /// The `data:` fields, joined by newlines.
    pub data: String,
    /// How many bytes the event occupied on the wire.
    pub len: usize,
    /// True when the payload was longer than [`MAX_DATA`] and was cut.
    pub truncated: bool,
}

#[derive(Debug, Default)]
pub struct Stream {
    buf: Vec<u8>,
    name: String,
    data: String,
    len: usize,
    started: bool,
    truncated: bool,
}

impl Stream {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feeds wire bytes, returning every event they completed.
    pub fn feed(&mut self, bytes: &[u8]) -> Vec<Event> {
        let mut out = Vec::new();
        self.buf.extend_from_slice(bytes);
        while let Some(pos) = self.buf.iter().position(|&byte| byte == b'\n') {
            let line: Vec<u8> = self.buf.drain(..=pos).collect();
            let text = String::from_utf8_lossy(&line).into_owned();
            self.line(text.trim_end_matches(['\r', '\n']), &mut out);
        }
        if self.buf.len() > MAX_LINE {
            // A line this long is not a stream a reader can follow: what is
            // here goes away rather than waiting for a newline that may never
            // come.
            self.buf.clear();
        }
        out
    }

    fn line(&mut self, line: &str, out: &mut Vec<Event>) {
        if line.is_empty() {
            // A blank line ends the event, and only an event that carried a
            // payload is dispatched — a comment or a lone `id:` announces
            // nothing.
            if !self.data.is_empty() {
                out.push(Event {
                    name: if self.name.is_empty() {
                        "message".to_string()
                    } else {
                        std::mem::take(&mut self.name)
                    },
                    data: std::mem::take(&mut self.data),
                    len: self.len,
                    truncated: self.truncated,
                });
            }
            self.name.clear();
            self.len = 0;
            self.started = false;
            self.truncated = false;
            return;
        }
        if line.starts_with(':') {
            // A comment is the keep-alive, and it is not an event.
            return;
        }
        let (field, value) = match line.split_once(':') {
            Some((field, value)) => (field, value.strip_prefix(' ').unwrap_or(value)),
            None => (line, ""),
        };
        if !matches!(field, "data" | "event" | "id" | "retry") {
            return;
        }
        self.started = true;
        self.len += line.len() + 1;
        match field {
            "event" => {
                if self.name.len() + value.len() <= MAX_DATA {
                    self.name.push_str(value);
                }
            }
            "data" => {
                if !self.data.is_empty() {
                    self.data.push('\n');
                }
                let room = MAX_DATA.saturating_sub(self.data.len());
                if value.len() > room {
                    self.data.push_str(&value[..floor_char(value, room)]);
                    self.truncated = true;
                } else {
                    self.data.push_str(value);
                }
            }
            _ => {}
        }
    }
}

/// The longest prefix of `value` no longer than `room` that is still a
/// character boundary: cutting a multi-byte character in half would make the
/// preview a decoding error instead of a shorter string.
fn floor_char(value: &str, room: usize) -> usize {
    let mut cut = room.min(value.len());
    while cut > 0 && !value.is_char_boundary(cut) {
        cut -= 1;
    }
    cut
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_event_is_dispatch_when_it_is_whole() {
        let mut stream = Stream::new();
        assert!(stream.feed(b"data: one\n").is_empty());
        let events = stream.feed(b"\n");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "one");
        assert_eq!(events[0].name, "message");
    }

    #[test]
    fn an_event_split_across_two_reads_is_one_event() {
        let mut stream = Stream::new();
        assert!(stream.feed(b"event: pi").is_empty());
        let events = stream.feed(b"ng\ndata: {\"a\":1}\n\n");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].name, "ping");
        assert_eq!(events[0].data, "{\"a\":1}");
    }

    #[test]
    fn several_events_in_one_read_come_back_in_order() {
        let mut stream = Stream::new();
        let events = stream.feed(b"data: 1\n\ndata: 2\n\ndata: 3\n\n");
        assert_eq!(
            events.iter().map(|e| e.data.as_str()).collect::<Vec<_>>(),
            vec!["1", "2", "3"]
        );
    }

    #[test]
    fn several_data_lines_are_one_payload() {
        let mut stream = Stream::new();
        let events = stream.feed(b"data: a\ndata: b\n\n");
        assert_eq!(events[0].data, "a\nb");
    }

    #[test]
    fn a_comment_is_not_an_event() {
        let mut stream = Stream::new();
        assert!(stream.feed(b": keep-alive\n\n").is_empty());
    }

    #[test]
    fn an_event_with_no_payload_is_not_dispatched() {
        let mut stream = Stream::new();
        assert!(stream.feed(b"event: ping\n\n").is_empty());
    }

    #[test]
    fn a_carriage_return_is_not_part_of_the_payload() {
        let mut stream = Stream::new();
        let events = stream.feed(b"data: one\r\n\r\n");
        assert_eq!(events[0].data, "one");
    }

    #[test]
    fn a_payload_longer_than_the_cap_is_counted_and_cut() {
        let mut stream = Stream::new();
        let long = "x".repeat(MAX_DATA + 100);
        let events = stream.feed(format!("data: {long}\n\n").as_bytes());
        assert_eq!(events[0].data.len(), MAX_DATA);
        assert!(events[0].truncated);
    }

    #[test]
    fn a_cut_never_splits_a_character() {
        let mut stream = Stream::new();
        let long = "ệ".repeat(MAX_DATA);
        let events = stream.feed(format!("data: {long}\n\n").as_bytes());
        assert!(events[0].data.chars().all(|c| c == 'ệ'));
    }
}
